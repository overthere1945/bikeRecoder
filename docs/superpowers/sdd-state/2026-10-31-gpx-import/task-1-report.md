# Task 1 보고 — core: 파일 풀기와 GPX 읽기

## 파일
- 수정 `core/.../gpx/GpxParser.kt`: DocumentBuilderFactory 설정을 `internal fun secureDocumentBuilderFactory()`로 분리. `parse`는 그 팩토리를 쓸 뿐 동작 동일(기존 4개 시험 통과).
- 신규 `core/.../importing/ImportUnpacker.kt`: `ImportProblem`, `ImportLimits`, `ImportItem`(Gpx/Unreadable/TooManyEntries), `ImportUnpacker.visit`.
- 신규 `core/.../importing/GpxReader.kt`: `GpxReadException`, `GpxTrack`, `GpxReader.read`.
- 신규 시험 `ImportUnpackerTest`(16), `GpxReaderTest`(19). 바이너리 픽스처 없음(ZIP·gzip은 시험 안에서 java.util.zip으로 생성).

## 설계 메모 / 브리프가 정하지 않은 부분에서 내린 결정
- 최상위 ZIP은 스트리밍(전체를 메모리에 올리지 않음). 그 외 항목은 `maxItemBytes + 1`에서 읽기를 끊어 TOO_LARGE. gzip은 압축본과 푼 결과 모두 한도 적용(압축 폭탄 방지), 한 번만 풀고 푼 내용이 GPX가 아니면(gzip·ZIP 포함) NOT_GPX.
- ZIP 항목 번호(`index`)와 `maxZipEntries`는 폴더를 제외한 항목 기준. 한도를 넘는 (N+1)번째 파일 항목을 만나면 `TooManyEntries(displayName)`를 내고 중단. 정확히 N개면 안내 없음.
- GPX 판별: 앞 4 KB에서 `<gpx` 또는 접두어 형태 `<g:gpx` 여는 태그를 정규식으로 찾는다(스펙은 `<gpx`만 언급, prefixedNamespace 시험과 맞추려고 접두어 허용). UTF-16 BOM이면 UTF-16으로 디코딩.
- 입력이 깨진 ZIP/IO 오류면 `Unreadable(UNREADABLE)`을 내고 끝낸다(항목 단위 오류는 그 항목만).
- `GpxReader`의 오류 분류: SAX 파싱 실패(깨진 XML·DOCTYPE 차단 포함)는 바이트가 `<gpx` 여는 태그를 가졌으면 UNREADABLE(잘렸거나 DOCTYPE), 아니면 NOT_GPX(XML 아님). 루트 localName이 `gpx`가 아니면 NOT_GPX. 메시지 문자열에 의존하지 않기 위해 이렇게 했다. DOCTYPE 차단은 기존과 같은 `disallow-doctype-decl`이며, 지원하지 않는 파서를 위해 EntityResolver도 예외를 던지게 해 외부 자원을 읽지 않는다.
- 시간은 `OffsetDateTime.parse`(Z/+09:00). 오프셋 없는 시각은 읽을 수 없는 시간으로 보고 버린다. 시간 없음/읽기 실패 점 수가 전체의 절반 초과(또는 점 0개)면 NO_TIME(정확히 절반은 통과). 좌표가 나쁜 점은 시간 없음 비율에 넣지 않는다.
- `droppedPoints = totalPoints - fixes.size` (시간·좌표 불량 + 같은 시간의 뒤쪽 중복 포함). 정렬은 안정 정렬 후 `distinctBy`라 파일에서 앞선 점이 남는다.
- `time`/`ele`/`type`/`name`은 바로 아래 자식에서만 찾는다(extensions 안의 같은 이름을 피함). `trkpt`/`trk`는 문서 전체에서 순서대로 모으고 wpt/rtept는 무시.
- `ImportItem.Gpx`는 ByteArray를 가지므로 equals/hashCode를 내용 비교로 재정의.
- `ImportLimits.maxRides`는 unpacker가 쓰지 않는다(호출하는 쪽이 센다) — KDoc에 명시.

## 시험
- `:core:test` 전체 통과. GpxReaderTest 19, ImportUnpackerTest 16, GpxParserTest 4, NavigationSessionTest 24.
- ImportUnpackerTest: binNamedFileIsSniffedAsGpx, bomAndWhitespaceBeforeGpx, prefixedRootIsGpx, htmlIsNotGpx, emptyFileIsNotGpx, gzipGpxIsUnpacked, gzipIsUnpackedOnlyOnce, brokenGzipIsUnreadable, zipYieldsEachGpxWithIndexAndName, zipSkipsFolders, nestedZipIsNotGpx, oversizeItemIsTooLarge, oversizeAfterGunzipIsTooLarge, zipOverEntryLimitStopsWithNotice, zipAtEntryLimitHasNoNotice, stravaLikeZipYieldsOnlyGpxAndNotices.
- GpxReaderTest: readsPointsTypeAndName, blankTypeAndNameAreNull, noTrkMeansNullTypeAndName, offsetTimesParse, sortsAndDropsDuplicateTimes, fewMissingTimesAreDropped, unparsableTimeCountsAsMissing, mostlyMissingTimesIsNoTime, exactlyHalfMissingIsStillReadable, noPointsIsNoTime, badCoordinatesDropped, missingElevationIsNull, multipleTrkAndTrksegMerged, routePointsAndWaypointsAreIgnored, prefixedNamespaceReads, doctypeIsRejected, nonGpxXmlIsNotGpx, plainTextIsNotGpx, truncatedGpxIsUnreadable.

## 우려
- TDD 순서: 구현을 시험보다 먼저 써서 "컴파일 실패 확인" 단계는 건너뜀(시험은 모두 통과).
- DOCTYPE 파일은 UNREADABLE로 분류(브리프는 `GpxReadException`만 요구). 다른 분류가 필요하면 한 줄 수정.
- DOM이라 50 MB GPX는 메모리를 많이 쓸 수 있음(브리프 결정 사항).
- `doctypeIsRejected`는 예외 발생으로 외부 파일이 결과에 안 들어감을 보이지만, 파일 접근 자체를 감시하지는 않는다.

## Fix round 1
- `GpxReader.read`: UTF-8 BOM과 ASCII 공백을 건너뛴 위치부터 파싱(`contentStart`, 복사 없이 offset으로). UTF-16 BOM이면 그대로 둔다. 시험 `leadingWhitespaceBeforeXmlDeclReads`(공백만, BOM+공백, BOM만).
- `parseTimeMillis`: `DateTimeException`(파싱 실패 포함)과 `ArithmeticException`을 잡아 null → 점을 버린다. 시험 `absurdTimeIsDropped`(`+999999999-12-31T00:00:00Z`).
- `ImportUnpacker.visit`: 읽기만 try/catch로 감싸고 visitor는 밖에서 호출. visitor의 IOException은 그대로 전파되고 한 번만 불린다. 시험 `visitorExceptionPropagates`(단일 파일, ZIP). ZIP의 `nextEntry` IOException도 `Unreadable(UNREADABLE)`로 처리(이 경우 index는 그 시점의 항목 번호).
- 오류 처리: 조용히 다시 던지는 `ErrorHandler`를 설치(표준 오류에 "[Fatal Error]" 출력 없음 확인), `newDocumentBuilder()`의 `ParserConfigurationException`은 UNREADABLE.
- 결과: `./gradlew :core:test` BUILD SUCCESSFUL. GpxReaderTest 21, ImportUnpackerTest 17, GpxParserTest 4, 실패 0.
