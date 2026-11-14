package ai.kilopace.app.ui

import ai.kilopace.app.R
import ai.kilopace.app.data.Fmt
import ai.kilopace.app.data.QrEncoder
import ai.kilopace.app.data.Report
import ai.kilopace.app.data.TripRecord
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** 운행 기록 표 + 약정 요약 + QR/CSV 내보내기. 정차 시에만 열린다. */
class LedgerFragment : Fragment(R.layout.fragment_ledger), Refreshable {

    private val act get() = requireActivity() as MainActivity
    private val adapter = TripAdapter { t -> confirmDelete(t) }
    private val storeListener: () -> Unit = { rebuild() }
    private val uxListener: (Boolean) -> Unit = { applyLock(it) }

    private fun confirmDelete(t: TripRecord) {
        if (act.app.ux.restricted) return
        AlertDialog.Builder(requireContext())
            .setTitle("이 운행 기록을 삭제할까요?")
            .setMessage("${TripRecord.formatDate(t.startAt)} · ${Fmt.km(t.distanceKm)} km\n되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ -> act.app.store.delete(t.id) }
            .setNegativeButton("취소", null)
            .show().apply {
                listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).mapNotNull { getButton(it) }.forEach { it.textSize = 22f }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        view.findViewById<Button>(R.id.btnExport).setOnClickListener { export() }
        act.app.store.addListener(storeListener)
        act.app.ux.addListener(uxListener)
        rebuild()
    }

    override fun onDestroyView() {
        act.app.store.removeListener(storeListener)
        act.app.ux.removeListener(uxListener)
        super.onDestroyView()
    }

    private fun applyLock(restricted: Boolean) {
        view?.findViewById<View>(R.id.txtLocked)?.visibility = if (restricted) View.VISIBLE else View.GONE
    }

    /** 차량 신호마다 불린다. 표는 저장소가 바뀔 때만 다시 그리고, 여기서는 요약과 잠금만 갱신한다. */
    override fun refresh() {
        if (view == null) return
        applyLock(act.app.ux.restricted)
        bindSummary()
    }

    private fun rebuild() {
        val v = view ?: return
        val trips = act.app.store.closed()
        adapter.submit(trips)
        v.findViewById<TextView>(R.id.txtPeriod).text =
            "운행 기록 ${trips.size}건 · 합계 ${Fmt.km(trips.sumOf { it.distanceKm ?: 0.0 })} km"
        bindSummary()
        applyLock(act.app.ux.restricted)
    }

    private fun summaryText(): String {
        val c = act.controller
        return Report.summary(c.paces(), c.odometerKm, System.currentTimeMillis(), act.app.store.closed().size)
    }

    private fun bindSummary() {
        view?.findViewById<TextView>(R.id.txtSummary)?.text = summaryText()
    }

    private fun export() {
        val v = view ?: return
        val file = Report.writeCsv(requireContext(), Report.csv(act.app.store.closed()))
        val payload = summaryText()
        runCatching { v.findViewById<ImageView>(R.id.imgQr).setImageBitmap(QrEncoder.encode(payload, 640)) }
        v.findViewById<TextView>(R.id.txtExportInfo).text =
            "QR: 약정 요약 ${payload.toByteArray().size} bytes (휴대폰 카메라로 읽기)\n운행 CSV: ${file.absolutePath}"
    }

    private class TripAdapter(private val onLongClick: (TripRecord) -> Unit) : RecyclerView.Adapter<TripAdapter.VH>() {
        private var items: List<TripRecord> = emptyList()
        fun submit(list: List<TripRecord>) { items = list; notifyDataSetChanged() }
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false) as TextView)
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val t = items[i]
            h.tv.setOnLongClickListener { onLongClick(t); true }
            h.tv.text = String.format(Locale.US, "%-16s %11s %12s %8s  %s",
                TripRecord.formatDate(t.startAt),
                Fmt.km(t.odoStartKm), Fmt.km(t.odoEndKm), Fmt.km(t.distanceKm) + " km",
                t.destName.ifBlank { "—" })
        }
    }
}
