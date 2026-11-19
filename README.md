# zomApo

마인크래프트 **야생 좀비 아포칼립스** 서버용 Fabric 모드. 서버 사이드 전용, Kotlin.

같은 서버에서 함께 도는 국가 모드는 별도 저장소 [simpleNationWar](https://github.com/menonng/simpleNationWar) 에 있다.
두 모드는 따로 빌드·배포하고, zomApo 가 simpleNationWar 의 공개 API(`snw-api`)를 선택적으로 읽는 한 방향 의존만 둔다.

> **현재 단계: 기획 문서.** 코드는 아직 없다. 테스트 대상은 Minecraft 26.3, 26.4 정식 출시 즉시 26.4 로 올린다. 서버 개장 목표는 2027년 1월.

## 한눈에 보기

- 몬스터로 분류되는 몹(중립 몹·보스·워든 제외)이 **좀비 계열로 바뀐다.** 바뀐 몹은 원래 몹의 로직과 기믹을 이어받고, 원래 드랍에 좀비 드랍이 더해진다.
- 처음부터 전부 좀비인 것은 아니다. **청크마다 감염 레벨(0~5)** 이 있고, 시즌 진행·국가 활동·정화 활동에 따라 오르내린다.
- 좀비가 많이 나오고 **변종**(러너, 비명꾼, 탱커, 알파 등)이 있다.
- 플레이어마다 **좀비 처치 수와 좀비 스코어**(좀비마다 점수가 다름)를 기록하고 인게임에서 볼 수 있다.
- 죽으면 소지품을 든 **망자 좀비**가 그 자리에 남는다(인벤세이브 대신).
- **스타포스 강화**와 **커스텀 인첸트**(데이터팩 기반)를 넣는다.
- 접속자는 **바닐라 클라이언트**로 들어온다. 모드·리소스팩 설치가 필요 없다.

## 문서

### 서버 전체 (두 모드 공통)
| 문서 | 내용 |
|---|---|
| [docs/server/README.md](docs/server/README.md) | 서버 마스터 플랜: 컨셉, 원칙, 결정 요약, 전체 구성도 |
| [docs/server/decisions.md](docs/server/decisions.md) | 질문별 결정과 근거 (주민작, 초기 정착지, 맵 공개, 목표, 인벤세이브, 모드 채택 여부 …) |
| [docs/server/modlist.md](docs/server/modlist.md) | 설치 모드·데이터팩 목록, 클라이언트 필요 여부, 26.3/26.4 지원 현황, 추가 추천 |
| [docs/server/version-policy.md](docs/server/version-policy.md) | 26.3 → 26.4 버전 정책과 업그레이드 절차 |
| [docs/server/simulation.md](docs/server/simulation.md) | 인원·활성 인구별 플레이 양상과 재미도 시뮬레이션 |
| [docs/server/roadmap.md](docs/server/roadmap.md) | 개발 일정과 시즌 1 운영 일정 |

### zomApo 설계
| 문서 | 내용 |
|---|---|
| [docs/design/zombification.md](docs/design/zombification.md) | 감염 레벨과 진척도 산정 방식 (원안 비교 포함) |
| [docs/design/mobs.md](docs/design/mobs.md) | 몹 변환표와 좀비 변종 |
| [docs/design/stats.md](docs/design/stats.md) | 좀비 처치 수·좀비 스코어, 점수표, 농장 악용 방지 |
| [docs/design/death-and-revenant.md](docs/design/death-and-revenant.md) | 사망 처리: 망자 좀비 |
| [docs/design/starforce.md](docs/design/starforce.md) | 스타포스 강화 |
| [docs/design/enchantments.md](docs/design/enchantments.md) | 커스텀 인첸트 |
| [docs/tech/architecture.md](docs/tech/architecture.md) | 기술 구조, 서버 사이드 제약과 해법, monun 저장소 패턴 대응표 |
| [docs/reference/monun.md](docs/reference/monun.md) | monun 공개 저장소 16개 전체 분석과 가져올 기법 |

### 도구
| 경로 | 내용 |
|---|---|
| [tools/sim/zomapo_sim.py](tools/sim/zomapo_sim.py) | 플레이 양상 시뮬레이터 (표준 라이브러리만 사용) |
| [tools/sim/results.md](tools/sim/results.md) | 시뮬레이터 출력 원본 |
