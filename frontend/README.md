# NetVerify dashboard (Phase 12)

React + TypeScript UI for the control-plane HTTP API.

```bash
# API must listen on :8080 (Compose api or spring-boot:run)
npm install
npm run dev
```

Vite proxies `/api` → `http://localhost:8080`.
