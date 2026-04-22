import sys
import zipfile

zip_path = sys.argv[1]
target = sys.argv[2]

with zipfile.ZipFile(zip_path) as zf:
    for info in zf.infolist():
        if info.filename.endswith(target):
            print(info.filename)
            print(info.file_size)
            break
    else:
        print("NOT_FOUND")
