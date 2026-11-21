"""몹마다 '좀비화' 수정 명세. 바닐라 원종 모델 위에 덧붙이는 것만 적는다(원종 실루엣 유지).

좌표는 바닐라 모델 공간(픽셀, y 아래, 앞쪽 -z), 붙는 뼈대 기준. 바닐라 CubeDefinition 과 같은 뜻.
- pose: 뼈대 기본 자세에 더하는 회전(도). 처진 머리, 저는 다리.
- add: 덧붙이는 큐브. mat 은 자체 부품 텍스처(parts.py)의 재료. tell 은 그 큐브가 알려 주는 기믹(docs/design/zombies.md '몹 좀비화' 3절).
- resize: 원종 큐브 길이 바꾸기(썩어 떨어진 다리·촉수). (뼈대, 큐브 번호, 축, 새 길이, 줄이는 쪽)
- remove: 원종 큐브 빼기(부러진 막대). retex: 원종 큐브를 부품 재료로 바꾸기(석회화된 막대).
이 파일은 우리 설계라 저장소에 넣는다. 원종 형상·텍스처는 실행하는 사람의 jar 에서 읽는다.
"""

SPECS = {
    'creeper': {
        'layer': 'minecraft:creeper#main', 'texture': 'creeper/creeper.png', 'style': 'flesh',
        'pose': {'head': (0, 0, 7), 'right_hind_leg': (0, 0, -4)},
        'add': [
            dict(bone='body', origin=[-3.4, 1.5, 1.6], size=[3.5, 3.5, 2.2], mat='spore', tell='포자 자루: 터지기 전에 부풀며 빛난다'),
            dict(bone='body', origin=[0.6, 4.5, 1.6], size=[2.6, 2.6, 1.8], mat='spore'),
            dict(bone='body', origin=[-2.2, 8.0, 1.6], size=[2, 2, 1.2], mat='spore'),
            dict(bone='body', origin=[-3.2, 3.6, -2.5], size=[6.4, 0.7, 0.7], mat='bone'),
            dict(bone='body', origin=[-3.0, 5.6, -2.5], size=[6.0, 0.7, 0.7], mat='bone'),
            dict(bone='head', origin=[3.6, -3.4, -2.2], size=[1.2, 3.4, 2.4], mat='flesh'),
            dict(bone='head', origin=[-2.4, -8.6, -1.4], size=[3, 0.8, 3], mat='wound'),
        ],
    },
    'spider': {
        'layer': 'minecraft:spider#main', 'texture': 'spider/spider.png', 'eyes': 'spider/spider_eyes.png', 'style': 'dark',
        'pose': {'right_middle_hind_leg': (0, 0, 12), 'head': (6, 0, -5)},
        'add': [
            dict(bone='body1', origin=[-3.2, -6.4, -2.6], size=[6.4, 3.0, 6.0], mat='flesh', tell='터진 배: 침 주머니. 거미줄 침을 뱉기 전에 꿈틀댄다'),
            dict(bone='body1', origin=[1.6, -5.4, 3.0], size=[2.2, 2.0, 2.2], mat='spore'),
            dict(bone='body1', origin=[-4.2, -4.9, -4.6], size=[1.6, 1.4, 1.6], mat='spore'),
            dict(bone='body1', origin=[4.7, -3.2, -2.2], size=[0.8, 6.0, 0.8], mat='bone'),
            dict(bone='body1', origin=[4.7, -3.2, 1.0], size=[0.8, 6.0, 0.8], mat='bone'),
            dict(bone='head', origin=[-4.6, -2.6, -6.4], size=[1.0, 3.0, 3.2], mat='wound'),
        ],
        'resize': [('right_front_leg', 0, 'x', 10.0, 'min')],
        'add_tip': [dict(bone='right_front_leg', origin=[-10.6, -0.6, -0.6], size=[1.2, 1.2, 1.2], mat='bone')],
    },
    'cave_spider': {'base': 'spider', 'layer': 'minecraft:cave_spider#main', 'texture': 'spider/cave_spider.png', 'scale': 0.7},
    'enderman': {
        'layer': 'minecraft:enderman#main', 'texture': 'enderman/enderman.png', 'eyes': 'enderman/enderman_eyes.png', 'style': 'dark',
        'pose': {'head': (9, 0, -6), 'hat': (0, 0, 0)},
        'add': [
            dict(bone='head', origin=[-3.0, -0.4, -4.4], size=[6.0, 3.2, 1.6], mat='jaw', tell='늘어진 턱: 순간이동 직전에 크게 벌어진다'),
            dict(bone='body', origin=[-3.6, 2.6, -2.5], size=[7.2, 0.8, 0.8], mat='bone'),
            dict(bone='body', origin=[-3.4, 4.6, -2.5], size=[6.8, 0.8, 0.8], mat='bone'),
            dict(bone='body', origin=[-3.0, 6.6, -2.5], size=[6.0, 0.8, 0.8], mat='bone'),
            dict(bone='left_arm', origin=[-1.5, 9.0, -1.5], size=[3.0, 4.0, 3.0], mat='flesh'),
            dict(bone='right_arm', origin=[-1.6, -3.4, -1.6], size=[3.2, 2.2, 3.2], mat='spore'),
        ],
    },
    'creaking': {
        'layer': 'minecraft:creaking#main', 'texture': 'creaking/creaking.png', 'eyes': 'creaking/creaking_eyes.png', 'style': 'tint',
        'pose': {'head': (0, 0, 6)},
        'add': [
            dict(bone='body', origin=[1.0, -1.0, -3.6], size=[4.0, 6.0, 1.0], mat='rot', tell='갈라진 줄기 속 썩은 심: 오래 쳐다보면 뿌리를 내민다'),
            dict(bone='body', origin=[0.4, -5.0, -2.4], size=[3.2, 2.0, 3.2], mat='fungus'),
            dict(bone='body', origin=[-5.6, -5.6, -2.0], size=[2.4, 1.6, 2.4], mat='fungus'),
            dict(bone='head', origin=[-3.4, -13.8, -3.4], size=[6.8, 1.0, 6.8], mat='moss'),
            dict(bone='right_leg', origin=[-3.4, 6.0, -1.9], size=[3.8, 3.0, 3.8], mat='moss'),
        ],
    },
    'slime': {
        'layer': 'minecraft:slime#main', 'outer': 'minecraft:slime#outer', 'texture': 'slime/slime.png', 'style': 'slime',
        'remove': [('cube', 0)],
        'add': [
            dict(bone='cube', origin=[-1.8, 18.2, -1.8], size=[3.6, 3.2, 3.6], mat='skull', tell='속에 비치는 해골: 작은 개체끼리 붙으면 다시 합쳐진다'),
            dict(bone='cube', origin=[-1.2, 21.3, -1.6], size=[2.4, 1.0, 2.0], mat='bone'),
            dict(bone='cube', origin=[1.0, 19.0, 0.6], size=[1.6, 1.6, 1.6], mat='flesh'),
        ],
    },
    'magma_cube': {
        'layer': 'minecraft:magma_cube#main', 'texture': 'slime/magmacube.png', 'style': 'warm',
        'add': [
            dict(bone='cube3', origin=[3.6, 19.0, -1.2], size=[1.6, 1.0, 2.4], mat='bone', tell='삐져나온 탄 뼈: 갈라질 때 뼛조각 파편'),
            dict(bone='cube5', origin=[-5.2, 21.0, 0.4], size=[1.6, 1.0, 1.8], mat='bone'),
            dict(bone='cube1', origin=[-1.0, 16.6, -5.0], size=[2.0, 1.4, 1.2], mat='bone'),
            dict(bone='cube7', origin=[-1.5, 23.6, -1.0], size=[3.0, 0.8, 2.0], mat='flesh'),
        ],
    },
    'phantom': {
        'layer': 'minecraft:phantom#main', 'texture': 'phantom/phantom.png', 'eyes': 'phantom/phantom_eyes.png', 'style': 'flesh',
        'pose': {'right_wing_tip': (0, 0, -8)},
        'resize': [('right_wing_tip', 0, 'x', 8.0, 'min')],
        'add': [
            dict(bone='left_wing_tip', origin=[0.0, -0.3, 1.0], size=[13.0, 0.6, 0.6], mat='bone', tell='찢어진 날개: 화살에 맞으면 떨어져 5초 기어 다닌다'),
            dict(bone='left_wing_tip', origin=[0.0, -0.3, 5.0], size=[11.0, 0.6, 0.6], mat='bone'),
            dict(bone='right_wing_tip', origin=[-8.6, -0.3, 1.0], size=[8.6, 0.6, 0.6], mat='bone'),
            dict(bone='body', origin=[-3.3, 0.8, -6.6], size=[5.6, 0.5, 0.7], mat='bone'),
            dict(bone='body', origin=[-3.3, 0.8, -4.2], size=[5.6, 0.5, 0.7], mat='bone'),
            dict(bone='body', origin=[-3.3, 0.8, -1.8], size=[5.6, 0.5, 0.7], mat='bone'),
            dict(bone='head', origin=[-2.0, 0.8, -4.6], size=[3.0, 1.8, 1.2], mat='flesh'),
        ],
    },
    'guardian': {
        'layer': 'minecraft:guardian#main', 'texture': 'guardian/guardian.png', 'style': 'flesh',
        'retex': [('spike2', 0, 'bone'), ('spike5', 0, 'bone'), ('spike8', 0, 'bone')],
        'add': [
            dict(bone='head', origin=[-8.4, 12.5, -3.0], size=[1.0, 4.5, 5.5], mat='wound', tell='찢긴 옆구리: 맞으면 탁한 피를 뿜어 앞을 가린다'),
            dict(bone='head', origin=[2.0, 6.4, -3.0], size=[3.2, 2.0, 3.2], mat='spore'),
            dict(bone='head', origin=[-4.4, 6.9, 2.2], size=[2.2, 1.4, 2.2], mat='spore'),
            dict(bone='head', origin=[6.6, 17.0, 2.0], size=[2.0, 3.0, 4.0], mat='flesh'),
        ],
    },
    'breeze': {
        'layer': 'minecraft:breeze#main', 'texture': 'breeze/breeze.png', 'eyes': 'breeze/breeze_eyes.png', 'style': 'tint',
        'retex': [('rod_3', 0, 'bone')],
        'add': [
            dict(bone='head', origin=[-2.2, -8.8, -2.2], size=[4.4, 1.0, 4.4], mat='core', tell='갈라진 핵: 돌풍이 터진 자리로 주변 좀비를 부른다'),
            dict(bone='head', origin=[3.7, -6.4, -1.2], size=[1.4, 3.0, 3.0], mat='fungus'),
            dict(bone='head', origin=[-5.2, -7.2, 0.6], size=[1.4, 2.0, 2.0], mat='spore'),
        ],
    },
    'ghast': {
        'layer': 'minecraft:ghast#main', 'texture': 'ghast/ghast.png', 'style': 'bone',
        'resize': [('tentacle1', 0, 'y', 6.0, 'max'), ('tentacle6', 0, 'y', 5.0, 'max')],
        'add': [
            dict(bone='body', origin=[-8.4, -4.0, -3.0], size=[1.0, 5.0, 6.0], mat='wound', tell='썩은 상처: 울부짖음이 멀리 퍼져 좀비를 모은다'),
            dict(bone='body', origin=[2.6, -8.6, -5.0], size=[6.0, 1.0, 4.0], mat='flesh'),
            dict(bone='body', origin=[4.6, -8.8, 2.6], size=[2.2, 1.4, 2.2], mat='spore'),
            dict(bone='body', origin=[-6.0, -8.8, 3.8], size=[2.0, 1.2, 2.0], mat='spore'),
            dict(bone='tentacle1', origin=[-1.2, 5.4, -1.2], size=[2.4, 1.2, 2.4], mat='flesh'),
            dict(bone='tentacle6', origin=[-1.2, 4.4, -1.2], size=[2.4, 1.2, 2.4], mat='flesh'),
        ],
    },
    'blaze': {
        'layer': 'minecraft:blaze#main', 'texture': 'blaze/blaze.png', 'style': 'warm',
        'remove': [('part2', 0), ('part9', 0), ('part11', 0)],
        'retex': [('part4', 0, 'bone'), ('part10', 0, 'bone')],
        'add': [
            dict(bone='head', origin=[-4.3, -4.6, -4.3], size=[8.6, 1.2, 8.6], mat='rot', tell='꺼져 가는 머리: 죽을 때 재 구름'),
            dict(bone='head', origin=[1.0, -5.6, -1.0], size=[2.4, 1.2, 2.4], mat='spore'),
            dict(bone='head', origin=[-4.6, -1.0, -2.0], size=[1.0, 3.0, 3.0], mat='wound'),
        ],
    },

    # ---- B. 사람형: 장비 레이어 덧씌움(자체 그림 '썩은 살갗', 대부분 투명) ----
    'skeleton': {
        'layer': 'minecraft:skeleton#main', 'texture': 'skeleton/skeleton.png', 'style': 'bone', 'method': 'B',
        'overlay': ['minecraft:skeleton#helmet', 'minecraft:skeleton#chestplate', 'minecraft:skeleton#leggings'],
        'pose': {'head': (8, 0, 6)},
        'add': [dict(bone='body', origin=[-3.4, 2.0, -1.6], size=[2.4, 3.0, 1.0], mat='flesh', tell='살점 붙은 뼈: 4블록 안에서는 활 대신 할퀸다')],
    },
    'stray': {'base': 'skeleton', 'layer': 'minecraft:stray#main', 'texture': 'skeleton/stray.png',
              'overlay': ['minecraft:stray#helmet', 'minecraft:stray#chestplate', 'minecraft:stray#leggings']},
    'bogged': {'base': 'skeleton', 'layer': 'minecraft:bogged#main', 'texture': 'skeleton/bogged.png',
               'overlay': ['minecraft:bogged#helmet', 'minecraft:bogged#chestplate', 'minecraft:bogged#leggings']},
    'wither_skeleton': {'base': 'skeleton', 'layer': 'minecraft:wither_skeleton#main', 'texture': 'skeleton/wither_skeleton.png', 'style': 'dark',
                        'overlay': ['minecraft:wither_skeleton#helmet', 'minecraft:wither_skeleton#chestplate', 'minecraft:wither_skeleton#leggings']},
    'piglin_brute': {
        'layer': 'minecraft:piglin_brute#main', 'texture': 'piglin/piglin_brute.png', 'style': 'flesh', 'method': 'B',
        'overlay': ['minecraft:piglin_brute#chestplate', 'minecraft:piglin_brute#leggings'],
        'add': [
            dict(bone='head', origin=[-5.4, -7.0, -2.0], size=[1.0, 3.0, 3.0], mat='wound', tell='찢긴 귀와 상처: 체력 절반 아래에서 광폭'),
            dict(bone='body', origin=[1.0, 2.0, -2.6], size=[3.0, 4.0, 1.0], mat='bone'),
        ],
    },
    # ---- C. 머리 칸 모델(일리저·마녀) ----
    'pillager': {
        'layer': 'minecraft:pillager#main', 'texture': 'illager/pillager.png', 'style': 'flesh', 'method': 'C',
        'add': [
            dict(bone='head', origin=[-4.4, -3.0, -4.6], size=[3.0, 3.6, 1.0], mat='jaw', tell='썩은 얼굴 가면(머리 칸 아이템)'),
            dict(bone='head', origin=[1.6, -9.0, -4.5], size=[2.6, 2.2, 0.8], mat='wound'),
            dict(bone='head', origin=[-2.0, -10.8, -1.0], size=[3.0, 1.0, 3.0], mat='spore'),
            dict(bone='head', origin=[3.6, -6.0, -1.6], size=[1.0, 4.0, 3.0], mat='flesh'),
        ],
    },
    'vindicator': {'base': 'pillager', 'layer': 'minecraft:vindicator#main', 'texture': 'illager/vindicator.png'},
    'evoker': {'base': 'pillager', 'layer': 'minecraft:evoker#main', 'texture': 'illager/evoker.png'},
    'illusioner': {'base': 'pillager', 'layer': 'minecraft:illusioner#main', 'texture': 'illager/illusioner.png'},
    'witch': {
        'layer': 'minecraft:witch#main', 'texture': 'witch/witch.png', 'style': 'flesh', 'method': 'C',
        'add': [
            dict(bone='head', origin=[-5.6, -10.6, -5.6], size=[2.4, 1.4, 2.4], mat='fungus', tell='모자에 핀 버섯: 역병 물약'),
            dict(bone='head', origin=[3.0, -10.8, 2.4], size=[2.0, 1.6, 2.0], mat='fungus'),
            dict(bone='head', origin=[-4.4, -4.0, -4.6], size=[2.6, 3.6, 1.0], mat='wound'),
        ],
    },
    # ---- D. 색 변형 텍스처 ----
    'shulker': {'layer': 'minecraft:shulker#main', 'texture': 'shulker/shulker_green.png', 'style': 'flesh', 'method': 'D',
                'add': [], 'tell_text': '부패 셜커: 초록 셜커 텍스처 자리를 바꾼다(새 모델 없음)'},
}
