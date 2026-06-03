import os

def replace_in_files(directory):
    for root, dirs, files in os.walk(directory):
        for file in files:
            if file.endswith('.kt') or file.endswith('.xml'):
                path = os.path.join(root, file)
                with open(path, 'r', encoding='utf-8') as f:
                    content = f.read()
                if 'com.fenyx.jiotv' in content:
                    content = content.replace('com.fenyx.jiotv', 'com.fenyx.jtv')
                    with open(path, 'w', encoding='utf-8') as f:
                        f.write(content)
                    print(f"Updated {path}")

replace_in_files('c:/Users/Ayush/Desktop/jiotv_go_tv/app/src')
