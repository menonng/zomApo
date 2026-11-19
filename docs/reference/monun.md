# monun 저장소 분석

[monun](https://github.com/monun) 의 공개 저장소 16개를 모두 확인했다(2026-11-19). 대부분 Paper(Bukkit) 플러그인용 Kotlin 코드이고, `obs-studio-dodeca` 와 `minecraft-server-launcher` 를 빼면 2021~2024년에 보관(archived)되었다.

> 코드 라이선스는 대부분 GPL-3.0 이다. **코드를 옮기지 않고 설계와 기법만 참고**한다. Fabric 에는 Bukkit 이벤트·`PersistentData`·`JavaPlugin` 이 없으므로 같은 생각을 믹스인·Fabric 이벤트·데이터 부착으로 다시 만든다.

## 1. 전체 목록

| 저장소 | 무엇인가 | 기술 | 마지막 커밋 | 이 서버에 가져올 것 |
|---|---|---|---|---|
| **series-survival** | '생존일지' 좀비 아포칼립스 방송용 플러그인. 생존자 → 좀비 → 슈퍼 좀비 → 하이퍼 좀비, 백신 | Paper 1.16.5, Kotlin 1.4, tap·kommand | 2021-04 | 상태 기계, 진영별로만 보이는 가짜 엔티티, 팀 색 발광, 설정 자동 리로드, 백신 연출 (2절) |
| **tap** | Paper 확장 라이브러리: 틱 스케줄러, 엔티티 단위 이벤트, 가짜 엔티티·투사체, 설정 리플렉션, 데이터 키, 템플릿, GitHub 자동 업데이트, 모장 API | Paper 1.18~1.20.1 | 2023-10 | 세 가지 시간 모드 스케줄러, 대상별 가짜 엔티티, 범위 검증 설정, 문자열 템플릿 (3절) |
| **kommand** | Brigadier 위 명령어 Kotlin DSL | Paper 1.17~1.20.1 | 2023-10 | `dynamic` 인수 + 자동완성 → `/info <닉네임\|마크이름>` (4절) |
| **invfx** | 상자 GUI Kotlin DSL (Frame, Slot, Pane, List) | Paper | 2023-07 | 국가 GUI 구조 그대로 (5절) |
| **psychics** | 능력자 플러그인. 능력 개념/인스턴스 분리, 스탯 공식, 피해 타입, 툴팁 빌더, 외부 jar 능력 로더 | Paper, Kotlin 1.5 | 2022-01 | 개념·인스턴스 분리, 피해 타입 ↔ 보호 인첸트, 툴팁 빌더, 자원 소유권 (6절) |
| **speculation** | 부동산 투기 보드게임. `core`(순수 게임 로직) + `plugin`(표시·입력) 두 계층, 코루틴 진행 | Paper 1.17.1, coroutines, heartbeat-coroutines | 2022-02 | **순수 로직 모듈 분리**, 타입 있는 다이얼로그 요청, 이벤트 어댑터 (7절) |
| **inv-captive** | '불편한 동거'. 모든 플레이어가 인벤토리 하나를 공유, 41칸이 잠겨 있고 칸마다 시드로 정한 블록을 처음 캐면 서버 전체에서 한 칸씩 해금 | Paper 1.16.5, NMS 리플렉션 | 2021-02 | 서버 공동 '발견 해금', 시드 고정 랜덤, 잠긴 칸 표시 (8절) |
| **paper-sample** | 단일 모듈 플러그인 템플릿. 버전 카탈로그(`libs.versions.toml`), clip/bundle jar 두 방식 | Paper 1.20.1 | 2023-08 | 버전 카탈로그 |
| **paper-sample-complex** | API / CORE(NMS 버전별 하위 프로젝트) / PLUGIN / PUBLISH 분리 템플릿, 이름 규칙 기반 구현 로더, CI(`./gradlew test`) | Paper 1.20.1 | 2023-08 | API·구현 분리(`snw-api`), CI |
| **paper-sample-lib** | 위의 이전 판(1.18) | Paper 1.18 | 2022-01 | — |
| **kotlin-plugin** | Kotlin 표준 라이브러리를 여러 플러그인이 공유하도록 런타임으로 제공하는 Bukkit 플러그인 | Bukkit | 2021-06 | Fabric 에서는 Fabric Language Kotlin 이 같은 역할 → **Kotlin 라이브러리를 각 모드에 넣지 않는다** |
| **server-script** | 서버 실행 셸 스크립트: 재시작 루프, 종료 후 tar 백업, Aikar JVM 플래그, 원격 디버그 포트, 플러그인 자동 다운로드 | Bash | 2021-11 | 운영 스크립트 (9절) |
| **minecraft-server-launcher** | 위의 후속판. 환경 변수 설정, JVM 종류(HotSpot/OpenJ9)별 GC 플래그 | Bash, jq | 2025-02 | 운영 스크립트 (9절) |
| **dynmap-assemble** | Dynmap 타일을 이어 붙여 거대한 지도 이미지 한 장을 만드는 파이썬 스크립트(포크) | Python, Pillow | 2022-04 | 시즌 결산 '전체 지도 포스터' (10절) |
| **obs-studio-dodeca** | 오디오 트랙을 6개에서 12개로 늘린 OBS Studio 32.2.2 포크 | C/C++ | 2026-11 | 시즌 하이라이트 녹화 시 참고 (10절) |
| OSSRH-70531 | 빈 저장소(메이븐 중앙 저장소 등록 확인용) | — | — | — |

## 2. series-survival (생존일지)

### 무엇을 하나

| 요소 | 동작 |
|---|---|
| 상태 | `Bio.Type` 열거형: 생존자 / 좀비 / 슈퍼 좀비 / 하이퍼 좀비. 열거형마다 생성자(`::Zombie`)를 들고 있어 상태 전환이 `setBio(type)` 한 줄 |
| 생명주기 | `onInitialize → applyAttribute → onAttach → onUpdate(매 틱) → onDetach`, 상태별 저장·불러오기 |
| 생존자 | 죽으면 좀비(또는 지정된 사람은 슈퍼 좀비)가 된다. 석궁으로 쏘는 **백신 폭죽**에 맞은 좀비는 생존자로 돌아온다 |
| 좀비 | 영구 독(독 피해는 무시) + 야간 투시, 배고픔 없음, 다이아몬드·방패 등 제작 금지, 길들이기 금지, 타격 시 위더. 몹은 좀비 플레이어를 먼저 노리지 않는다 |
| 슈퍼 좀비 | 무작위 생존자를 관전 모드로 **추적**(대상에게 "슈퍼 좀비가 당신을 추적합니다"), 다른 좀비들을 자기 위치로 **소환**, 항상 발광 |
| 하이퍼 좀비 | 높은 점프, 점프할 때 폭죽, 매 순간 무작위 팀 색으로 발광 테두리 색이 바뀜 |
| 하이퍼 백신 | 사용하면 로켓 발사 연출(폭죽 200틱) 후 4틱마다 좀비를 한 명씩 해방, 마지막에 하이퍼 좀비 소멸 |
| 진영별 가짜 엔티티 | 생존자의 눈높이에 **좀비 머리를 쓴 발광 갑옷 거치대**를 띄우는데, 이 가짜 엔티티는 **좀비 진영에게만** 보인다(`fakeEntityServerForZombie`). 좀비는 벽 너머 생존자를 본다 |
| 서버 설정 | 첫 접속·리스폰 위치를 월드보더 안 무작위, 채팅 전면 금지, 사망·접속 메시지 숨김, 서버 목록에서 접속자 수 숨김, 운영자 외 명령 금지 |
| 설정 | `@Config` 리플렉션, **설정 파일의 수정 시각이 바뀌면 다음 틱에 자동으로 다시 읽음** |

### 가져올 것

| 기법 | 적용 |
|---|---|
| 열거형 + 생성자 참조 상태 기계, 생명주기 훅 | zomApo `ZombieForm` 계층 ([architecture.md](../tech/architecture.md)) |
| **진영별로만 보이는 가짜 엔티티** | 망자 위치 표시를 **본인·자국민에게만** 보이는 발광 마커로 ([death-and-revenant.md](../design/death-and-revenant.md)), 등불 인첸트 |
| **팀 색으로 발광 테두리 색 바꾸기** | 알파 좀비를 전용 스코어보드 팀에 넣어 빨간 발광 테두리로 ([mobs.md](../design/mobs.md)) |
| 슈퍼 좀비의 추적 | 알파 좀비가 무작위 접속자 한 명을 '추적 대상'으로 정하고 알림 ("알파가 당신을 쫓고 있습니다") |
| 하이퍼 백신 연출 | **치료제 완성 연출**: 수도에서 로켓 발사 → 감염 지역이 차례로 정화되는 장면 ([decisions.md](../server/decisions.md) 4-4) |
| 설정 수정 시각 감지 자동 리로드 | 두 모드 모두 `/reload` 명령과 함께 적용 |
| 무작위 시작 위치 | 우리는 반랜덤 공정 배치로 변형 ([decisions.md](../server/decisions.md) 2번) |

### 가져오지 않을 것

| 기법 | 이유 |
|---|---|
| 플레이어가 좀비가 되는 감염 | 활성 3~4명 서버에서는 생존자 쪽이 금방 무너진다. 망자 좀비로 바꿨다 |
| 채팅 금지, 사망·접속 메시지 숨김, 접속자 수 숨김 | 방송용 긴장 장치다. 우리 서버는 플레이 시간의 63~78% 를 자국민 없이 보내므로 "누가 들어와 있는지"를 알아야 함께 논다. 채팅은 전서구 지연(couriersRoost)으로 긴장만 살린다 |
| NMS 버전 고정 코드 | Fabric + Mojang 이름으로 대체 |

## 3. tap

| 기능 | 내용 | 적용 |
|---|---|---|
| `Ticker` 세 가지 모드 | **plank**: 호출 횟수(게임 틱)로 시간이 흐름, 렉이 걸리면 함께 느려짐 / **precision**: 실제 시간 기준, 밀린 틱을 몰아서 실행 / **flex**: 실제 시간 기준, 밀린 실행은 한 번만 | 몹 AI·쿨다운은 게임 틱, **블러드문 30분·거점전 1시간·06:00 재계산은 실제 시간(flex)**, 망자 보호 10분은 실제 시간 |
| `EntityEventManager` | 이벤트에서 '관련 엔티티'를 뽑아(Provider) 그 엔티티에 등록된 핸들러만 호출 | 믹스인이 엔티티 부착의 `ZombieForm` 훅을 바로 호출 |
| `FakeEntityServer` | 패킷으로만 존재하는 엔티티. 보여 줄 플레이어를 넣고 빼며, 생성·제거 거리 설정 | 대상별 마커(망자, 등불, 거점 점령 구역 경계) |
| `FakeProjectile` | 가짜 투사체 궤적 | 토사자 침·주술 좀비 연출에 참고 |
| `ConfigSupport` | `@Config`, `@RangeInt/@RangeDouble` 로 **범위 밖 값을 잘라냄** | 설정 데이터 클래스에 범위 검증 |
| `PersistentDataKeychain` | 타입 있는 데이터 키(기본형, 직렬화 객체, UUID, 위치) | Fabric 데이터 부착 타입 + 아이템 `custom_data` 타입 접근자 |
| `TemplateSupport` | 설정 문자열에 `$이름`, `${식}` 치환 | 모든 안내 문구를 설정 파일 템플릿으로 (한국어 문구 수정에 코드 수정 불필요) |
| `GitHubSupport` | 최신 GitHub 릴리스의 jar 를 받아 교체 | 서버 업데이트 스크립트가 우리 모드의 릴리스 jar 를 받아 오게 (9절) |
| `MojangAPI` | 이름 → UUID, 스킨 프로필 | 디스코드 봇의 마크 이름 확인 |
| `TrailSupport` | 두 점 사이를 일정 간격으로 잇는 점들 | 파티클 궤적(비명꾼 음파, 알파 도약) |
| `tap-dongle` | NMS 버전별 구현 모듈 | 쓰지 않음(단일 버전 정책) |

## 4. kommand

- `register("명령") { then("하위") { then("이름" to string()) { executes { … } } } }` 형태의 DSL. 인수는 `val name: String by context` 로 꺼낸다.
- 인수 타입이 바닐라 Brigadier 인수(정수 범위, 플레이어, 위치, 아이템 …)를 거의 다 감싼다.
- **`dynamic { }` 인수**: 직접 해석 함수와 자동완성 목록을 주는 인수.
  - 적용: `/info` 의 대상 인수를 `dynamic` 처럼 만들어 **마크 이름과 커스텀 닉네임을 모두** 받고 자동완성에 둘 다 띄운다.
- `requires { }` 로 권한 분기 → 운영자 명령 숨김.

## 5. invfx

```
frame(6줄, 제목) {
  slot(x, y) { item = …; onClick { … } }
  pane(minX, minY, maxX, maxY) { onClick { x, y, event -> … } }
  list(minX, minY, maxX, maxY, 세로?, { 데이터 }) { transform { 데이터 → 아이템 }; onClickItem { … } }   // page, index 로 넘김
  onOpen / onClose / onClickBottom / onClickOutside
}
```

국가 GUI 를 이 구조로 만든다.

| invfx 요소 | 국가 GUI |
|---|---|
| `list` + `page` | 국민 탭(머리 아이콘 목록), 국고 기록 |
| `pane` + 좌표 클릭 | 영토 탭(9×4 청크 격자에서 x, y → 청크 오프셋) |
| `slot` | 탭 버튼, 화살표, 확인 버튼 |
| `onClickBottom` | 플레이어 인벤토리 쪽 클릭(연구 납품 시 손 아이템 선택) |

## 6. psychics

| 요소 | 내용 | 적용 |
|---|---|---|
| `AbilityConcept` / `Ability` | 설정에서 읽는 **개념**과 플레이어마다 생기는 **인스턴스** 분리 | `FormConcept`(JSON) / `FormBehavior` |
| `EsperStatistic` | 수치를 "공격력 × 1.2 + 레벨 × 0.5" 같은 **스탯의 선형 결합**으로 설정에 적음 | 변종 체력·피해를 레벨에 대한 식으로 설정(`hp: {base: 20, perLevel: 4}`) |
| `DamageType` | 근접·원거리·화염·폭발 피해를 **대응하는 보호 인첸트**와 묶음 | 변종 특수 공격(토사자 침, 팽창체 구름, 자폭, 송곳니)이 바닐라 피해 유형(투사체, 폭발, 마법)을 쓰게 해서 **보호 인첸트가 그대로 먹히게** |
| `TooltipBuilder` | 제목·머리말·본문·꼬리말 + 템플릿으로 아이템 설명 조립 | 스타포스 lore, 바이러스 결정 설명 |
| 자원 소유권 | `Psychic` 이 자기 틱 스케줄러·가짜 투사체·리스너·가짜 엔티티를 **소유**하고 해제 시 함께 정리 | `FormBehavior` 가 만든 예약 작업·가짜 엔티티는 엔티티가 죽거나 언로드될 때 함께 정리(누수 방지) |
| 시전(채널링)·끊김 | 시전 시간 동안 보스바, 피해를 받으면 끊김 | 거점 점령 진행(분쟁 시 정지), 스타캐치 |
| `AbilityLoader` | 능력을 별도 jar 로 만들어 클래스 로더로 불러옴 | 쓰지 않음(JSON 개념으로 충분) |

## 7. speculation

가장 구조적으로 배울 것이 많은 저장소다.

| 요소 | 내용 | 적용 |
|---|---|---|
| **두 계층 분리** | `speculation-core`: 마인크래프트를 모르는 순수 게임 로직(보드, 말, 구역, 규칙, 예외로 흐름 제어), JUnit·Mockito 테스트 / `speculation-plugin`: 진행을 보여 주고 플레이어 입력을 받음 | **zomApo `logic` 모듈**(감염 레벨 식, 점수 보정, 강화 확률, 변종 가중치), **simpleNationWar `logic` 모듈**(K/D/A 판정, 영토 상한, 다각형 병합, 관계 규칙)을 마인크래프트 의존 없이 만들고 단위 테스트한다 |
| **타입 있는 다이얼로그** | `GameDialog<R>`(주사위, 매입 여부, 베팅 액수, 대상 선택 …)를 코어가 `suspend` 로 요청하고, 플러그인이 등록한 처리기가 플레이어 응답을 돌려줌. 처리기가 없으면 `default()` | 바닐라 다이얼로그를 `suspend fun <R> ask(player, dialog, timeout, default)` 로 감싼다. 관계 선언 확인, 직위 변경, 인게임 상호평가가 같은 틀 |
| `GameEventAdapter` | 이벤트 클래스 → 처리기 등록, 코어가 이벤트를 내면 표시 계층이 연출 | `snw-api` 이벤트 버스, 디스코드 모드 연동 |
| `Attachable` | 코어 객체에 표시 계층 객체를 붙여 둠(`piece.attachment<PaperPiece>()`) | 로직 객체 ↔ 마인크래프트 객체 연결 |
| 코루틴 진행 | 전체 게임 흐름을 코루틴으로, `Dispatchers.Heartbeat`(서버 틱에 붙은 디스패처, `io.github.monun:heartbeat-coroutines`)로 메인 스레드 작업 | 서버 스레드 디스패처 + `delayTicks(n)` 로 블러드문 웨이브·연출을 순서대로 기술 |

## 8. inv-captive (불편한 동거)

- 모든 플레이어의 인벤토리 필드를 **하나의 리스트로 바꿔 끼워** 진짜로 공유한다.
- 41칸이 방벽 아이템으로 잠겨 있고, 각 칸은 시드로 섞은 블록 종류 하나와 짝지어져 있다. 누군가 그 블록을 처음 캐면 서버 전체에 폭죽과 공지가 나가고 한 칸이 풀린다.
- 시드는 설정 파일에 저장해서 재시작해도 같은 짝이 유지된다.

| 기법 | 적용 |
|---|---|
| 서버 공동 '발견 해금' | **국고 칸 해금**(선택): 국고 54칸 중 처음엔 27칸, 거점 첫 점령·다른 지역 표본 첫 확보 같은 '발견'마다 9칸씩 열림. 비동기 협동에 작은 목표를 더한다 |
| 시드 고정 랜덤 | 감염 둥지 위치 후보, 보급 투하 지점 순서를 월드 시드에서 파생해 재시작해도 같게 |
| 잠긴 칸을 아이템으로 표시 | GUI 의 잠긴 탭·칸은 방벽 아이템 + 설명(해금 조건)으로 |

## 9. server-script, minecraft-server-launcher

| 기법 | 적용 |
|---|---|
| **재시작 루프** | 스크립트가 서버를 무한 루프로 띄우고, 게임 안에서 재시작을 요청하면 표식 파일(`.start`)을 만들어 종료 후 다시 시작 | 운영 스크립트에 그대로. 매일 05:50 경고 → 06:00 재시작(감염 레벨 재계산과 맞춤) |
| **종료 후 백업** | 서버가 꺼진 직후 `tar` 로 월드 압축 | 재시작 루프 사이에 백업(7일 보관) |
| Aikar JVM 플래그 | 메모리 12 GB 미만/이상에 따라 G1 설정을 달리함 | 힙 6~8 GB 이므로 표준 세트. Java 25 에서 세대별 ZGC 와 비교 측정 후 결정 |
| 원격 디버그 포트 | `-agentlib:jdwp` | 테스트 서버에서만 |
| 플러그인 자동 다운로드 | 설정한 URL 의 jar 를 시작 전에 받음 | 우리 모드는 GitHub 릴리스에서 받는 업데이트 스크립트로 (tap `GitHubSupport` 와 같은 생각) |

## 10. dynmap-assemble, obs-studio-dodeca

- **dynmap-assemble**: 지도 타일을 이어 붙여 한 장의 거대한 이미지를 만든다. 적용: **시즌 결산 때 squaremap 타일(또는 BlueMap 평면도)을 이어 붙인 '시즌 1 전체 지도'** 를 영토 경계를 얹어 디스코드 명예의 전당에 올린다.
- **obs-studio-dodeca**: 오디오 트랙 12개 OBS. 시즌 하이라이트를 녹화할 때 게임 소리·디스코드 음성·각 마이크를 트랙별로 나눠 담으면 편집이 쉽다. 서버 개발과는 무관한 참고 사항.

## 11. 반영한 문서

| 문서 | 반영 내용 |
|---|---|
| [tech/architecture.md](../tech/architecture.md) | `logic` 모듈 분리, 스케줄러 시간 모드, 자원 소유권, 템플릿 문구, Kotlin 라이브러리 미포함, 버전 카탈로그·CI |
| [design/death-and-revenant.md](../design/death-and-revenant.md) | 본인·자국민에게만 보이는 망자 마커 |
| [design/mobs.md](../design/mobs.md) | 알파 좀비 팀 색 발광·추적, 특수 공격의 바닐라 피해 유형 |
| [server/decisions.md](../server/decisions.md) | 치료제 완성 연출 |
| [server/modlist.md](../server/modlist.md) | 운영 스크립트 |
| [server/roadmap.md](../server/roadmap.md) | 시즌 전체 지도 포스터 |
| simpleNationWar [tech/architecture.md](https://github.com/menonng/simpleNationWar/blob/HEAD/docs/tech/architecture.md) | `logic` 모듈, 타입 있는 다이얼로그, GUI DSL, `/info` 동적 인수 |
| simpleNationWar [design/nation.md](https://github.com/menonng/simpleNationWar/blob/HEAD/docs/design/nation.md) | 국고 칸 해금(선택) |
