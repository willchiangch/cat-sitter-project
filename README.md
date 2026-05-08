# 貓咪到府保母 (Cat Sitter)

為專職貓咪保母打造的雙角色（**保母 / 飼主**）預約與照護管理系統。

本專案目前已清除所有技術債，正處於**全新重構與架構設計階段**。

---

## 系統架構規劃

本系統採前後端分離 (Monorepo) 架構：

- **前端 (Frontend)**：預計使用 React 建置，未來將支援 PWA (Progressive Web App)，提供離線快取與桌面安裝體驗。
- **後端 (Backend)**：預計使用 Java 提供 RESTful API 服務。
- **資料庫 (Database)**：PostgreSQL (本地開發統一使用 Docker Compose 啟動)。

---

## 專案目錄結構

```text
cat-sitter-project/
├── frontend/          # (規劃中) React 前端應用程式
├── backend/           # (規劃中) Java 後端 API 服務
├── docs/              # 專案文件、系統設計 (SD) 與系統分析 (SA) 文件
├── docker-compose.yml # 本地 PostgreSQL 資料庫環境設定
└── README.md          # 專案說明文件
```

> **備註**：專案根目錄下可能包含設定檔或 CI/CD 配置（如 `.github/`、`.gitignore` 等），此處僅列出核心業務目錄。

---

## 本地開發環境

### 1. 啟動資料庫

本地端依賴 Docker 啟動 PostgreSQL 15+：

```bash
docker-compose up -d
```

### 2. 前後端服務 (建置中)

前端與後端的專案初始化將在系統分析 (SA) 與系統設計 (SD) 完成後，透過腳手架 (如 `create-vite`, Spring Initializr) 重新建立。
