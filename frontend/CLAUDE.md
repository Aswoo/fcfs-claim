# Frontend — React Native

## 기술 스택
- React Native (New Architecture)
- Navigation: React Navigation v7
- 클라이언트 상태: Zustand
- 서버 상태: TanStack Query
- 언어: TypeScript

## 파일 구조
```
src/
  features/     # 기능별 모듈 (claim, event 등)
  shared/       # 공통 컴포넌트, 훅
  navigation/   # 네비게이션 설정
  services/     # API 레이어 (백엔드 연동)
  types/        # 공통 타입 정의
```

## 컴포넌트 규칙
- 함수형 컴포넌트만 사용, 클래스 컴포넌트 금지
- 비즈니스 로직은 커스텀 훅으로 분리 — 컴포넌트에 직접 작성 금지
- 인라인 스타일 금지 — StyleSheet.create()만 사용
- 라우트 레벨에 에러 바운더리 필수
- 배럴 익스포트(index.ts)로 외부 노출

## 성능 규칙
- FlatList 사용 시 keyExtractor, getItemLayout 필수
- renderItem에 인라인 화살표 함수 금지 (매 렌더마다 새 참조 생성)
- 무거운 계산은 useMemo, 콜백은 useCallback으로 메모이제이션
- 이미지에 width/height 명시 필수

## API 연동
- 백엔드 baseURL: http://localhost:8081
- 모든 API 호출은 services/ 레이어에서만
- 서버 상태(재고, 이벤트)는 TanStack Query로 관리
- 에러 응답(409 STOCK_EXHAUSTED, 409 ALREADY_CLAIMED)은 공통 핸들러로 처리
