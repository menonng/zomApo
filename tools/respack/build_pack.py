"""서버 리소스팩 zip 만들기: respack/(직접 그린 것) + tools/respack/out/rig/assets(리그, 모장 텍스처에서 나와 저장소 제외).
결과 tools/respack/out/zomapo-pack.zip — 서버 폴더에 두고 config/zomapo-pack.properties 로 호스팅한다(공개 저장소에 올리지 않는다).
같은 내용이면 같은 zip(파일 순서·시각 고정)이라 SHA-1 이 바뀌지 않아 플레이어가 다시 받지 않는다."""
import hashlib, os, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = [os.path.join(ROOT, 'respack'), os.path.join(ROOT, 'tools', 'respack', 'out', 'rig')]
OUT = os.path.join(ROOT, 'tools', 'respack', 'out', 'zomapo-pack.zip')


def main():
    files = {}
    for base in SRC:
        if not os.path.isdir(base):
            print('없음(건너뜀):', base); continue
        for d, _, fs in os.walk(base):
            for f in fs:
                if f.endswith(('.py', '.pyc', '.md')):
                    continue
                full = os.path.join(d, f)
                files[os.path.relpath(full, base).replace(os.sep, '/')] = full
    if 'pack.mcmeta' not in files:
        sys.exit('pack.mcmeta 가 없습니다')
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name in sorted(files):
            zi = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
            with open(files[name], 'rb') as fh:
                z.writestr(zi, fh.read())
    data = open(OUT, 'rb').read()
    print(f'{OUT}  {len(files)}개 파일  {len(data) // 1024} KB  sha1 {hashlib.sha1(data).hexdigest()}')


if __name__ == '__main__':
    main()
