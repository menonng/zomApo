# zomApo

마인크래프트 **야생 좀비 아포칼립스** 서버용 Fabric 모드. 서버 사이드 전용, Kotlin.

같은 서버에서 함께 도는 국가 모드는 별도 저장소 [simpleNationWar](https://github.com/menonng/simpleNationWar) 에 있다.
두 모드는 따로 빌드·배포하고, zomApo 가 simpleNationWar 의 공개 API(`snw-api`)를 선택적으로 읽는 한 방향 의존만 둔다.

> **현재 단계: 개발 시작.** 빌드 골격과 순수 로직(감염 레벨, 변종 추첨, 좀비 점수, 스타포스)이 들어왔다. 테스트 대상은 Minecraft 26.3, 26.4 정식 출시 즉시 26.4 로 올린다. 서버 운영 예정은 2027년 1월 중순 ~ 2월 초(약 3주).

## 빌드

JDK 25 가 필요하다. Gradle 과 마인크래프트·Fabric 파일은 래퍼가 받는다.

```
./gradlew buildAll          # logic 단위 테스트 + 모드 jar (mod/build/libs/)
./gradlew :mod:runServer    # 26.3 전용 서버를 띄워 모드가 켜지는지 확인(스모크 테스트)
```

| 모듈 | 내용 |
|---|---|
| `logic` | 마인크래프트를 모르는 순수 Kotlin. 문서의 식을 옮기고 JUnit 으로 고정한다 |
| `mod` | Fabric 서버 모드. `logic` 을 jar 안에 포함한다 |

## 한눈에 보기

- 몬스터로 분류되는 몹 중 비공격적 몹·보스·워든을 뺀 **적대·중립 몬스터가 감염 레벨에 따라 계열별로 좀비화된다**(Lv1 스켈레톤·거미 → Lv2 크리퍼·엔더맨 등 → Lv3 우민·가디언·네더 몹 등). 원종 엔티티와 기믹은 그대로, 좀비 특성과 좀비화 외형(서버 리소스팩)을 더하고, 원종 드랍과 좀비 드랍을 둘 다 준다.
- 처음부터 전부 좀비인 것은 아니다. **청크마다 감염 레벨(0~5)** 이 있고, 시즌 진행·국가 활동·정화 활동에 따라 오르내린다. 레벨이 오르면 밤 스폰이 늘고(×1.5~2.0), Lv2 부터 낮에도 나오고 타지 않으며 좀비 떼가 찾아온다. 낮에는 모든 좀비가 태양광 알레르기로 약해진다.
- 좀비가 많이 나오고 **변종**(러너, 비명꾼, 탱커, 알파 등)이 있다.
- 플레이어마다 **좀비 처치 수와 좀비 스코어**(좀비마다 점수가 다름)를 기록하고 인게임에서 볼 수 있다.
- 죽으면 소지품을 든 **망자 좀비**가 그 자리에 남는다(인벤세이브 대신). 주인만 쓰러뜨릴 수 있다.
- **스타포스 강화**와 **커스텀 인첸트**(데이터팩 기반)를 넣는다.
- 접속자는 **바닐라 클라이언트**로 들어온다. 모드 설치는 필요 없고, 서버 리소스팩만 자동으로 받는다.

## 문서

통합 문서 4개가 줄기이고, 근거·검토가 길어 따로 둔 상세 문서가 있다.

### 통합 문서 (먼저 읽을 것)

| 문서 | 목차 |
|---|---|
| [서버 기획서](docs/server/README.md) | 개요(전제·원칙·결정 요약·구성) · 일정 · 버전 정책 · 모드 목록·서버 사양 |
| [좀비](docs/design/zombies.md) | 감염 레벨(레벨별 규칙·스폰률·태양광 알레르기·좀비 떼·네더 레벨·둥지) · 몹 좀비화(레벨별 계열·출처 규칙·루트·변종) |
| [플레이어 시스템](docs/design/player.md) | 좀비 통계 · 망자 좀비 · 스타포스 · 커스텀 인첸트 |
| [기술 구조](docs/tech/architecture.md) | 구조(모듈·서버 사이드 제약과 해법) · 좀비화 외형(리소스팩 선택·리소스팩 없는 외형·리그) |

### 상세 문서

| 문서 | 내용 |
|---|---|
| [decisions.md](docs/server/decisions.md) | 질문별 결정과 근거 전체 (주민작, 정착지, 맵 공개, 목표, 인벤세이브, 모드 채택, 월드 크기, 편성, 플랫폼, 인원 재검토, 겉날개, 보상) |
| [simulation.md](docs/server/simulation.md) | 인원·활성 인구별 플레이 양상 시뮬레이션 |
| [performance.md](docs/server/performance.md) | 서버 성능 실측: RAM·CPU, 최적화 모드, Fabric 대 Paper·Folia, 9600X 기준 설정 |
| [privacy.md](docs/server/privacy.md) | 개인정보와 데이터 보관·노출·동의 |
| [bloodmoon-and-cure.md](docs/design/bloodmoon-and-cure.md) | 블러드문·치료제 검토와 시간 단위 규칙 |
| [monun.md](docs/reference/monun.md) | monun 공개 저장소 16개 분석 |

### 도구
| 경로 | 내용 |
|---|---|
| [tools/sim/zomapo_sim.py](tools/sim/zomapo_sim.py) | 플레이 양상 시뮬레이터 (표준 라이브러리만 사용) |
| [tools/sim/results.md](tools/sim/results.md) | 시뮬레이터 출력 원본 |
| [tools/sim/heat_compare.py](tools/sim/heat_compare.py) | 국가 영토 레벨 기준 비교: 처치 수·스코어·기본 스코어 ([heat-compare.md](tools/sim/heat-compare.md)) |
| [tools/sim/team_sim.py](tools/sim/team_sim.py) | 국가 편성 방식 비교 ([team-results.md](tools/sim/team-results.md)) |
| [tools/sim/starforce_sim.py](tools/sim/starforce_sim.py) | 스타포스 비용·운 분포 |
| [tools/sim/load_sim.py](tools/sim/load_sim.py) | 서버 부하 시뮬레이터: 동시 접속·흩어짐 → 힙·틱 시간 ([load-results.md](tools/sim/load-results.md)) |
| [tools/respack/zombify_textures.py](tools/respack/zombify_textures.py) | 바닐라 텍스처 → 좀비화 시안 텍스처(빌드 때 실행, Mojang 텍스처는 저장소에 넣지 않음) |
| [tools/perf/bench.py](tools/perf/bench.py) | 서버 부하 실측: Carpet 봇으로 틱·힙·GC 측정. 자기 PC 에서도 실행 가능 ([results.md](tools/perf/results.md)) |
