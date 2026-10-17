import glob
import re

for fpath in glob.glob('app/src/test/java/**/*.kt', recursive=True):
    with open(fpath, 'r', encoding='utf-8') as f:
        content = f.read()
    if 'MainViewModel.kt' in content:
        # Match functionBody(source, "...") or functionBody(viewModel, "...") or functionBody(mainViewModelSource(), "...")
        matches = re.findall(r'functionBody\s*\(\s*(?:mainViewModelSource\(\)|viewModel|source)\s*,\s*\"([^\"]+)\"', content)
        if matches:
            print(fpath)
            for m in matches:
                print('   functionBody on VM:', m)
