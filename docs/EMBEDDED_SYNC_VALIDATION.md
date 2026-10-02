# Embedded snapshot 필드 제외 검증

`mapKey("code").excludeSourceFields("code", "parentId")`처럼 관계를 결정하는 필드를 저장 snapshot에서 제외해도 원본 document에서 Map 키와 연결 대상을 찾아야 한다. 기존 구현은 Map 키를 이미 필드가 제거된 snapshot에서 읽어 쓰기를 생략할 수 있었다. 원본에서 관계를 판단하고 저장 payload에만 필드 제외를 적용하도록 수정했다.

DB 없는 회귀 테스트는 실제 ChangeStreamHub → EmbeddedSyncEngine → 드라이버 update 경로를 사용하며, 직접/중첩 Map 키 제외, 상위 document 제외, link 없는 관계, 원본 document 보존을 확인한다. 일반 테스트 **128개 전부 통과**했다. 기존 reservation 테스트 세 개도 실제 구독 이후 취소/정상 완료를 검증하도록 시점을 바로잡았다.

## 사용자 실행 MongoDB 테스트

JDK 21과 기존 `TEST_CLUSTER_NAME`, `TEST_USERNAME`, `TEST_PASSWORD`, `TEST_URL` 환경변수를 사용해 이 저장소 루트에서 실행한다.

```sh
./gradlew mongoEmbeddedSyncTest
```

PowerShell에서는 `./gradlew.bat mongoEmbeddedSyncTest`를 사용한다. 새 작업은 기존 `ReactiveMongoDslThreeFeaturesMigrationSafetyIntegrationTest.embeddedSynchronizationConvergesAcrossCollectionMapSingleMoveDeleteAndMultiHopRelations` 한 메서드를 실행한다. 일반 `test`와 `mongoMigrationTest`에는 이 클래스가 포함되지 않아 별도 작업을 추가했다.

검증 범위는 collection/map/single 동기화, 부모 이동, Map 키 변경, 삭제, 여러 단계 전파와 제외 필드의 실제 저장 여부다. 생성한 전용 테스트 DB만 사용하고 기존 fixture의 정리 절차를 따른다. replica set/change stream을 지원하는 실제 테스트 DB가 필요하다.

결과 위치:

- `build/reports/tests/mongoEmbeddedSyncTest/index.html`
- `build/test-results/mongoEmbeddedSyncTest/`

클라우드에서는 작업 등록의 dry-run만 확인했다. 실제 MongoDB 실행 결과는 사용자 검증 후 반영한다.
