import UIKit
import Capacitor
import CoreLocation
import UserNotifications

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        GymGeofence.shared.start()
        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        GymGeofence.shared.reloadAndRearm()
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        GymGeofence.shared.reloadAndRearm()
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesBegan(touches, with: event)
        let statusBarRect = UIApplication.shared.statusBarFrame
        guard let touchPoint = event?.allTouches?.first?.location(in: self.window) else { return }
        if statusBarRect.contains(touchPoint) {
            NotificationCenter.default.post(name: .capacitorStatusBarTapped, object: nil)
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        NotificationCenter.default.post(name: .capacitorDidRegisterForRemoteNotifications, object: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NotificationCenter.default.post(name: .capacitorDidFailToRegisterForRemoteNotifications, object: error)
    }
}

/// Automatic gym check-in/out via iOS region monitoring.
/// Config is written by the web app (Capacitor Preferences, key "gymGeofence",
/// stored in UserDefaults as "CapacitorStorage.gymGeofence") only on mom's phone.
/// Check-in/out calls go straight to the backend RPC, so they work even when
/// the app is relaunched in the background by a region event.
final class GymGeofence: NSObject, CLLocationManagerDelegate {

    static let shared = GymGeofence()

    private let regionId = "com.arsh.gymcheckin.gym"
    private let defaultsKey = "CapacitorStorage.gymGeofence"
    private let rpcBase = "https://vlbzvwmbcjxihlcgkped.supabase.co/rest/v1/rpc/"
    private let rpcKey = "sb_publishable_WTYpx355WH8AjHrSwgTFOg_ZW0nXele"

    private let manager = CLLocationManager()
    private var cfg: Cfg?
    private var pendingCheckIn: Bool?

    private struct Cfg: Codable {
        var code: String
        var lat: Double
        var lon: Double
        var radius: Double
    }

    func start() {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        reloadAndRearm()
        NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification,
                                               object: nil, queue: .main) { [weak self] _ in
            self?.reloadAndRearm()
        }
    }

    func reloadAndRearm() {
        loadConfig()
        ensureAuthorization()
        rearm()
    }

    private func loadConfig() {
        guard let raw = UserDefaults.standard.string(forKey: defaultsKey),
              let data = raw.data(using: .utf8),
              let c = try? JSONDecoder().decode(Cfg.self, from: data) else {
            cfg = nil
            return
        }
        cfg = c
    }

    private func ensureAuthorization() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse:
            manager.requestAlwaysAuthorization()
        default:
            break
        }
    }

    private func rearm() {
        for r in manager.monitoredRegions { manager.stopMonitoring(for: r) }
        guard let c = cfg,
              CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self),
              manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse
        else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        let radius = min(max(c.radius, 100), manager.maximumRegionMonitoringDistance)
        let region = CLCircularRegion(center: CLLocationCoordinate2D(latitude: c.lat, longitude: c.lon),
                                      radius: radius, identifier: regionId)
        region.notifyOnEntry = true
        region.notifyOnExit = true
        manager.startMonitoring(for: region)
    }

    // MARK: CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        if status == .authorizedWhenInUse { manager.requestAlwaysAuthorization() }
        rearm()
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard region.identifier == regionId else { return }
        beginEvent(checkIn: true)
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard region.identifier == regionId else { return }
        beginEvent(checkIn: false)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let checkIn = pendingCheckIn, let loc = locations.last else { return }
        pendingCheckIn = nil
        postEvent(checkIn: checkIn, lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard let checkIn = pendingCheckIn, let c = cfg else { return }
        pendingCheckIn = nil
        postEvent(checkIn: checkIn, lat: c.lat, lon: c.lon)
    }

    // MARK: backend call

    private func beginEvent(checkIn: Bool) {
        guard cfg != nil, pendingCheckIn == nil else { return }
        pendingCheckIn = checkIn
        manager.requestLocation()
        // Fallback if no fix arrives in time: use the region center.
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self = self, let checkIn = self.pendingCheckIn else { return }
            self.loadConfig()
            guard let c = self.cfg else { self.pendingCheckIn = nil; return }
            self.pendingCheckIn = nil
            self.postEvent(checkIn: checkIn, lat: c.lat, lon: c.lon)
        }
    }

    private func postEvent(checkIn: Bool, lat: Double, lon: Double) {
        loadConfig()
        guard let c = cfg else { return }
        let endpoint = checkIn ? "gym_check_in" : "gym_check_out"
        var body: [String: Any] = ["p_code": c.code]
        if checkIn { body["p_lat"] = lat; body["p_lon"] = lon }
        var req = URLRequest(url: URL(string: rpcBase + endpoint)!)
        req.httpMethod = "POST"
        req.setValue(rpcKey, forHTTPHeaderField: "apikey")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)

        let taskId = UIApplication.shared.beginBackgroundTask(expirationHandler: nil)
        URLSession.shared.dataTask(with: req) { [weak self] data, _, _ in
            defer { UIApplication.shared.endBackgroundTask(taskId) }
            guard let self = self, let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            let ok = json["ok"] as? Bool ?? false
            if ok {
                self.notify(checkIn ? "Checked in at the gym" : "Checked out - well done today!")
            }
            // A "far" rejection on entry (rare GPS edge at the boundary) is dropped:
            // the app is only awake for seconds, so no reliable retry exists.
            // The next boundary crossing, app open, or manual tap covers it.
        }.resume()
    }

    private func notify(_ text: String) {
        let content = UNMutableNotificationContent()
        content.title = "Gym check-in"
        content.body = text
        content.sound = .default
        let req = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
    }
}
