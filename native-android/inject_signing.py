p = 'android/app/build.gradle'
s = open(p).read()
lines = [
    "    def gymUploadStore = System.getenv(\"UPLOAD_STORE_FILE\")",
    "    if (gymUploadStore != null) {",
    "        signingConfigs {",
    "            release {",
    '                storeFile file(gymUploadStore)',
    '                storePassword System.getenv("UPLOAD_STORE_PASSWORD")',
    '                keyAlias System.getenv("UPLOAD_KEY_ALIAS")',
    '                keyPassword System.getenv("UPLOAD_KEY_PASSWORD")',
    "            }",
    "        }",
    "    }",
]
sc = "\n".join(lines) + "\n"
anchor = '    buildTypes {'
assert anchor in s, 'buildTypes anchor missing'
head, tail = s.split(anchor, 1)
rel = '        release {'
assert rel in tail, 'release anchor missing'
tail = tail.replace(rel, rel + '\n            if (gymUploadStore != null) { signingConfig signingConfigs.release }', 1)
open(p, 'w').write(head + sc + anchor + tail)
print('signing config injected (env-guarded)')
