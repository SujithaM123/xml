import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import GeneratePage from './pages/GeneratePage'
import CreateXsdPage from './pages/CreateXsdPage'
import RegistryPage from './pages/RegistryPage'
import HistoryPage from './pages/HistoryPage'

export default function App() {
  return (
    <BrowserRouter>
      <div className="app-shell">
        <header className="top-nav">
          <div className="brand">xml-toolkit</div>
          <nav>
            <NavLink to="/" end>
              Generate XMLs
            </NavLink>
            <NavLink to="/create-xsd">Create XSD</NavLink>
            <NavLink to="/registry">Registry</NavLink>
            <NavLink to="/history">History</NavLink>
          </nav>
        </header>
        <main className="page-content">
          <Routes>
            <Route path="/" element={<GeneratePage />} />
            <Route path="/create-xsd" element={<CreateXsdPage />} />
            <Route path="/registry" element={<RegistryPage />} />
            <Route path="/history" element={<HistoryPage />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
