#!/usr/bin/env python3
# Adopt the UIScene lifecycle (required for apps built with the iOS 27 SDK).
# Runs from the Capacitor "capacitor:sync:after" hook; idempotent.
import plistlib, sys, os
p = 'ios/App/App/Info.plist'
if not os.path.exists(p):
    print('add-scene: no', p, '(skipping)'); sys.exit(0)
d = plistlib.load(open(p, 'rb'))
sb = d.get('UIMainStoryboardFile', 'Main')
d['UIApplicationSceneManifest'] = {
    'UIApplicationSupportsMultipleScenes': False,
    'UISceneConfigurations': {
        'UIWindowSceneSessionRoleApplication': [{
            'UISceneConfigurationName': 'Default Configuration',
            'UISceneDelegateClassName': '$(PRODUCT_MODULE_NAME).SceneDelegate',
            'UISceneStoryboardFile': sb,
        }]
    }
}
plistlib.dump(d, open(p, 'wb'))
print('add-scene: UIApplicationSceneManifest set, storyboard =', sb)
