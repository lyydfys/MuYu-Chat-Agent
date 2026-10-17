import glob
import re

for fpath in glob.glob('app/src/test/java/**/*.kt', recursive=True):
    with open(fpath, 'r', encoding='utf-8') as f:
        content = f.read()
    if 'MainViewModel.kt' in content:
        # Find variable assigned from MainViewModel.kt
        # e.g. val source = sourceFile("...MainViewModel.kt")
        # val vm = ...
        var_names = re.findall(r'val\s+(\w+)\s*=\s*(?:sourceFile\([^\)]*MainViewModel\.kt[^\)]*\)|mainViewModelSource\(\))', content)
        for v in var_names:
            print(f'{fpath} (variable {v}):')
            for line in content.splitlines():
                if f'{v}.' in line or f'({v},' in line or f'({v})' in line:
                    if any(k in line for k in ['assert', 'contains', 'indexOf', 'functionBody']):
                        print('   ', line.strip())
