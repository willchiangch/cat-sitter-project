import React, { useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import { useServiceStore } from '../../store/serviceStore'
import { compressImage } from '../../utils/imageUtils'
import storageApi from '../../api/storage'

const ReportEditor = () => {
  const { t } = useTranslation()
  const { activeVisit, updateReport } = useServiceStore()

  if (!activeVisit) return null

  return (
    <section className="space-y-6 px-1">
      <div className="flex items-center gap-2 mb-4">
        <span className="material-symbols-outlined text-primary text-xl">edit_note</span>
        <h3 className="font-headline font-extrabold text-xl text-on-surface tracking-tight">
          {t('service_panel.report_header')}
        </h3>
      </div>

      <div className="space-y-4">
        {/* Text Report */}
        <textarea 
          value={activeVisit.sitterNotes || ''}
          onChange={(e) => updateReport(e.target.value)}
          placeholder={t('service_panel.report_placeholder')}
          className="w-full h-32 p-4 bg-surface-container-low border-none rounded-2xl text-sm font-body focus:ring-2 focus:ring-primary/20 placeholder:opacity-40 transition-shadow"
        />
        <p className="text-[10px] text-on-surface-variant font-body opacity-60 ml-1 leading-relaxed">
           此內容將作為服務總結日誌呈現給家長，建議描述貓咪今天的精神與食慾狀況。
        </p>
      </div>
    </section>
  )
}

export default ReportEditor
