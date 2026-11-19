# 기술 구조

## 1. 스택

| 항목 | 값 | 근거 |
|---|---|---|
| 언어 | Kotlin 2.4.x | 요구 사항. FLK 가 싣는 표준 라이브러리 버전에 맞춘다 |
| Minecraft | 26.3 → 26.4 | [version-policy.md](../server/version-policy.md) |
| Java | 25 | 26.x 요구 |
| Fabric Loader | 0.19.3 이상 | |
| Fabric API | `0.161.0+26.3` | |
| Fabric Language Kotlin | `1.14.2+kotlin.2.4.21` | |
| Gradle 플러그인 | `net.fabricmc.fabric-loom` (리매핑 없음, Mojang 이름) | 26.x 는 Yarn 없음 |
| 환경 | `"environment": "server"` | 바닐라 클라이언트 접속 |
| 설정 직렬화 | kotlinx.serialization (JSON) | |

외부 런타임 의존(필수)은 Fabric API 와 FLK 뿐이다. simpleNationWar(`snw-api`)는 선택 의존이다.

## 2. 서버 사이드 제약과 해법

| 하고 싶은 것 | 막히는 이유 | 해법 |
|---|---|---|
| 새 좀비 몹 | 새 엔티티 타입은 바닐라 클라이언트가 모른다 | 바닐라 엔티티 + Fabric 데이터 부착 + 장비·`scale` 속성·머리 아이템으로 외형 |
| 새 아이템(바이러스 결정) | 새 아이템 타입도 마찬가지 | 바닐라 베이스 아이템 + `item_model`·`custom_name`·`custom_data` 컴포넌트 |
| 새 상태 효과(감염) | 상태 효과 레지스트리 추가 불가 | 허기 + 나약함 조합으로 표현 |
| 커스텀 인첸트 효과 | 인첸트 정의는 클라이언트로 동기화 | 정의엔 바닐라 컴포넌트만, 효과는 모드가 인첸트 유무로 처리 |
| UI | 클라이언트 화면 추가 불가 | 상자 메뉴 GUI, 바닐라 다이얼로그(입력 폼), 액션바, 보스바, 타이틀 |
| 감염 지역의 하늘·안개 색 | 리소스팩 없이 불가 | 파티클·소리·액션바로 대신. 바이옴 교체 방식은 시즌 2 후보(아래 8절) |

## 3. 모듈과 패키지

단일 Gradle 프로젝트로 시작한다. 26.4 로 올린 뒤에는 26.3 을 유지하지 않으므로 버전별 서브프로젝트는 만들지 않는다.

```
zomApo/
├─ build.gradle.kts, gradle.properties, settings.gradle.kts
├─ src/main/kotlin/dev/menonng/zomapo/
│  ├─ ZomApo.kt                     진입점, 서비스 연결
│  ├─ config/                       설정 데이터 클래스, 다시 읽기
│  ├─ infection/                    감염 레벨 계산(전역 램프, 국가 열기, 정화, 영역 판정), 둥지, 블러드문
│  ├─ conversion/                   변환 규칙(태그), 치환·감염·기수 처리, 스폰 훅
│  ├─ form/                         ZombieForm 계층: 변환형·변종의 개념(설정)과 행동(런타임)
│  ├─ ai/                           이식용 목표(goal) 래퍼: 활, 석궁, 포션 투척, 송곳니, 벽 굴착…
│  ├─ loot/                         원본 + 좀비 루트 이중 굴림, 바이러스 결정
│  ├─ stats/                        좀비 통계 SavedData, 점수 계산, 농장 감쇠
│  ├─ revenant/                     망자 좀비
│  ├─ starforce/                    강화 규칙, GUI
│  ├─ enchant/                      커스텀 인첸트 효과 처리
│  ├─ integration/snw/              snw-api 연동(선택), 통계 제공자
│  ├─ integration/map/              감염 레이어(지도 모드가 있을 때)
│  ├─ gui/                          상자 메뉴 DSL
│  ├─ command/                      관리자 명령 (Brigadier Kotlin DSL)
│  └─ util/                         틱 스케줄러, 코루틴 디스패처, 시간
├─ src/main/java/…/mixin/           믹스인 (자바)
├─ src/main/resources/
│  ├─ fabric.mod.json, zomapo.mixins.json
│  └─ data/zomapo/ …                태그, 루트 테이블, 인첸트 데이터팩
└─ src/gametest/                    서버 게임테스트
```

## 4. monun 저장소에서 가져올 패턴

[monun](https://github.com/monun) 의 저장소는 Paper 플러그인이고 모두 보관(archived) 상태다. 코드는 GPL-3.0 이므로 **복사하지 않고 설계만 참고**한다. Fabric 은 Bukkit 이벤트가 없으므로 같은 생각을 믹스인 + 부착으로 옮긴다.

| monun 쪽 | 무엇을 하는가 | zomApo 에서 |
|---|---|---|
| series-survival `Bio` | `enum Type(key, displayName, creator)` 로 생존자·좀비·슈퍼 좀비·하이퍼 좀비를 상태 기계처럼 다룬다. `onAttach/onDetach/onUpdate/applyAttribute`, 상태마다 YAML 저장 | `ZombieForm` 봉인 계층(일반·변종·치환형·감염형·망자). 같은 생명주기 훅(`attach`, `detach`, `tick`, `applyAttributes`, `save/load`)을 엔티티 부착에 묶는다 |
| series-survival `SurvivalConfig` | `@Config` 리플렉션으로 설정 필드를 자동 읽기/쓰기 | kotlinx.serialization 데이터 클래스 + `/zomapo reload` |
| series-survival `Zombie.Wand` | 아이템별 쿨다운을 틱으로 관리하고 `setCooldown` 으로 표시 | 26.x 아이템 `use_cooldown` 컴포넌트와 서버 틱 시계 |
| series-survival `HyperVaccine` | 틱마다 진행되는 연출 태스크(폭죽 → 차례로 해방) | 블러드문·둥지 파괴 연출을 틱 태스크로 |
| tap `EntityEventManager` | **엔티티 단위** 이벤트 리스너 등록(그 엔티티에 일어난 일만 받기) | 믹스인이 부착된 `ZombieForm` 의 `onHurt/onAttack/onTarget/onDeath` 로 바로 전달. 엔티티 단위 디스패치 |
| tap `Ticker` | 틱 기반 스케줄러 | 서버 틱 스케줄러 + 서버 스레드 코루틴 디스패처(`delayTicks(n)`) |
| tap `FakeEntity` | 패킷으로만 존재하는 가짜 엔티티 | 등불 인첸트의 플레이어별 발광, 경고 표시 같은 '그 사람에게만 보이는' 연출 |
| psychics `AbilityConcept` / `Ability` | **정적 설정(개념)** 과 **런타임 인스턴스** 분리. 개념은 설정 파일에서 읽고, 인스턴스는 플레이어마다 생긴다 | `FormConcept`(JSON: 체력, 속도, 크기, 장비, 점수, 레벨별 가중치) / `FormBehavior`(엔티티마다). 밸런스 조정 = JSON 수정 |
| psychics `TestResult` | 능력 사용 가능 여부를 봉인 클래스로 반환 | 강화 시도 가능 여부(재료 부족, 최대 성 등)를 봉인 결과로 |
| kommand | Brigadier 위 Kotlin DSL (`then("name" to string()) { executes { … } }`) | 작은 자체 DSL. 관리자 명령만 있으므로 가볍게 |
| invfx | 상자 GUI Kotlin DSL (Frame, Button, Pane, List) | 강화 GUI 용 상자 메뉴 DSL. simpleNationWar 와 같은 모양으로 맞춘다 |

## 5. 데이터 저장

| 데이터 | 위치 | 이유 |
|---|---|---|
| 변환 정보, 망자 소지품 | 엔티티 부착(저장형) | 엔티티와 함께 언로드·로드 |
| 좀비 통계 | `SavedData` `zomapo_stats` (UUID 키) | 오프라인 조회, 국가 합계 |
| 감염 상태 | `SavedData` `zomapo_infection` | 전역 램프, 국가별 일일 스냅샷, 이벤트 보정, 둥지 |
| 강화 정보 | 아이템 `custom_data` | 아이템과 함께 이동 |

`SavedData` 는 코덱으로 직렬화한다. 디스코드 봇·결산용 JSON 내보내기는 simpleNationWar 쪽 디스코드 모드가 `snw-api` 통계 제공자를 통해 가져간다.

## 6. simpleNationWar 연동

의존 방향은 **zomApo → snw-api** 한쪽뿐이다.

| 필요 | 방법 |
|---|---|
| 플레이어의 국가, 국가 목록, 영토 청크, 관계 | `snw-api` 의 `NationApi` 를 `compileOnly` 로 참조, 실행 시 `FabricLoader.isModLoaded("snw")` 확인 |
| 영토 변경 알림(접경 BFS 재계산) | `NationApi.events().onClaimChanged` |
| `/info`·국민 목록에 좀비 통계 표시 | zomApo 가 `snw:stat_provider` 엔트리포인트로 `StatProvider` 구현을 등록 |
| 망자 보호 시간의 자국민 판정 | `NationApi.sameNation(a, b)` |

simpleNationWar 가 없으면: 모든 땅을 황야로 계산하고, 자국민 판정은 '본인만', 통계는 관리자 명령으로만 본다.

## 7. 테스트

- **서버 게임테스트**: 변환 확률 경계, 루트 이중 굴림, 점수 보정, 망자 생성·회수, 강화 확률 표.
- **헤드리스 서버 기동 CI**: InfraCraft 의 modpack 워크플로처럼 실제 서버를 띄워 모드가 켜지는지 확인한다. 26.4 업그레이드 때 가장 먼저 깨지는 지점을 잡는다.
- **시뮬레이터**: 밸런스 수치를 바꿀 때 [tools/sim](../../tools/sim) 을 같이 돌린다.

## 8. 시즌 2 후보

- **감염 바이옴**: 데이터팩 바이옴은 바닐라 클라이언트도 받으므로, 감염 레벨이 높은 청크의 바이옴을 하늘·안개·풀 색이 다른 '감염된 ○○' 변형으로 바꾸는 방식. 원본 바이옴의 스폰·온도를 복제해야 해서 작업량이 크다.
- **Polymer 도입**: 커스텀 모델(서버 리소스팩)이 필요해질 때.
