# FCFS-Claim Frontend

선착순 한정 수량 지급 시스템 — React Native 앱

---

## 기술 스택

| 항목 | 기술 |
|------|------|
| 프레임워크 | React Native (Expo) |
| 언어 | TypeScript |
| 네비게이션 | React Navigation v7 |
| 클라이언트 상태 | Zustand |
| 서버 상태 | TanStack Query |
| 빌드 | Expo CLI |

---

## 시작하기

### 요구사항

- Node.js 18+
- Expo Go 앱 (Android / iOS)
- 백엔드 서버 실행 중 (포트 8081)

### 설치 및 실행

```bash
npm install
npx expo start --port 8082
```

Expo Go 앱에서 QR 코드를 스캔하면 앱이 실행된다.

> 백엔드 서버(포트 8081)와 같은 Wi-Fi에 연결되어 있어야 API 연동이 된다.

---

## 화면 흐름

```
EnterScreen → WaitingScreen → ReadyScreen → ClaimScreen
  (진입)         (대기)        (입장 완료)    (상품 선택)
```

| 화면 | 설명 |
|------|------|
| `EnterScreen` | 이벤트 상태(SCHEDULED/ACTIVE/ENDED), 현재 대기 인원, 남은 수량 표시. 입장하기 버튼으로 대기열 진입 |
| `WaitingScreen` | 내 순번 표시, 진행률 바, Polling으로 순번 변화 구독 |
| `ReadyScreen` | 차례 도달 시 5분 카운트다운 시작, 시간 내 상품 선택으로 이동 |
| `ClaimScreen` | 상품 목록, 재고 상태, 선착순 신청 버튼 |

---

## API 연동

백엔드 baseURL: `http://localhost:8081`

| 화면 | API |
|------|-----|
| EnterScreen | `GET /api/v1/events/{id}/status` (이벤트 상태) |
| EnterScreen | `POST /api/v1/queue/enter` (대기열 입장) |
| WaitingScreen | `GET /api/v1/queue/status` (순번/토큰 폴링) |
| ClaimScreen | `GET /api/v1/events/{id}/products` (상품 목록) |
| ClaimScreen | `POST /api/v1/claim` (수령) |

---

## 프로젝트 구조

```
src/
├── constants/
│   └── colors.ts              # 컬러 토큰
├── features/
│   ├── claim/
│   │   └── ClaimScreen.tsx
│   └── queue/
│       ├── EnterScreen.tsx
│       ├── WaitingScreen.tsx
│       └── ReadyScreen.tsx
├── navigation/
│   └── AppNavigator.tsx
├── services/                  # API 레이어 (백엔드 연동)
├── shared/
│   └── components/            # 공통 컴포넌트
│       ├── Header.tsx
│       ├── Banner.tsx
│       ├── SectionHeader.tsx
│       ├── ProductCard.tsx
│       ├── StockBar.tsx
│       ├── StatusBadge.tsx
│       └── BottomActionBar.tsx
└── types/
    └── index.ts
```
