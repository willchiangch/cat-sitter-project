import { useState } from 'react'

function App() {
  const [dbData, setDbData] = useState(null)
  const [loading, setLoading] = useState(false)

  const fetchHello = async () => {
    setLoading(true)
    try {
      // 去敲我們剛剛寫好的 Java API
      const response = await fetch('http://localhost:8080/api/hello')
      const data = await response.json()
      setDbData(data)
    } catch (error) {
      console.error("連線失敗:", error)
      setDbData({ status: "error", message: "無法連線到後端，請確認 Java 有啟動且 CORS 有加喔！" })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="max-w-md mx-auto h-screen relative overflow-hidden bg-gray-100 flex flex-col items-center justify-center p-4">
      <div className="bg-white p-8 rounded-2xl shadow-lg w-full text-center border-t-8 border-amber-500">
        <h1 className="text-2xl font-bold text-gray-800 mb-2">喵保母系統開發中 🐈</h1>
        <p className="text-gray-500 mb-6 text-sm">全端連線測試儀表板</p>
        
        <button 
          onClick={fetchHello}
          disabled={loading}
          className="w-full bg-amber-500 hover:bg-amber-600 text-white font-bold py-3 px-4 rounded-lg transition-colors mb-6 disabled:opacity-50 cursor-pointer"
        >
          {loading ? '連線中...' : '呼叫 Java 後端 API'}
        </button>

        {dbData && (
          <div className="text-left bg-gray-50 p-4 rounded-lg border border-gray-200 overflow-auto">
            <span className="text-xs font-bold text-gray-400 mb-1 block">後端回傳結果：</span>
            {dbData.status === 'success' ? (
              <>
                <p className="text-sm text-green-600 font-bold mb-2">✅ {dbData.message}</p>
                <p className="text-xs text-gray-600">
                  <span className="font-bold">資料庫時間：</span><br/>
                  {dbData.database_time}
                </p>
              </>
            ) : (
              <p className="text-sm text-red-500 font-bold">❌ {dbData.message}</p>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export default App