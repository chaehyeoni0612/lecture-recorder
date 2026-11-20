package com.lecturerec.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate

class SettingsActivity : AppCompatActivity() {

    private val models = mutableListOf<String>()
    private val schedule = mutableListOf<ScheduleEntry>()
    private lateinit var modelBox: LinearLayout
    private lateinit var schedBox: LinearLayout
    private lateinit var keyEdit: TextInputEditText
    private lateinit var nameEdit: TextInputEditText
    private lateinit var groqEdit: TextInputEditText
    private lateinit var driveBox: LinearLayout

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            if (r.accessToken != null) onDriveAuthorized(r.accessToken!!)
            else Toast.makeText(this, "드라이브 연결이 취소됐습니다", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "드라이브 연결이 취소됐습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectDrive() {
        Identity.getAuthorizationClient(this).authorize(Drive.request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    val pi = r.pendingIntent
                    if (pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    r.accessToken?.let { onDriveAuthorized(it) }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "드라이브 연결 실패: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun onDriveAuthorized(token: String) {
        DrivePrefs.setConnected(this, true)
        renderDrive()
        Thread {
            val mail = try { Drive.email(token) } catch (e: Exception) { "" }
            DrivePrefs.setEmail(applicationContext, mail)
            runOnUiThread { if (!isFinishing) renderDrive() }
        }.start()
        SyncWorker.enqueue(this)
    }

    private fun copyMyFolderLink() {
        Thread {
            val link = try {
                val token = Drive.token(applicationContext)
                val id = DrivePrefs.rootId(applicationContext).ifEmpty {
                    Drive.folder(token, Drive.ROOT_NAME, null).also { DrivePrefs.setRoot(applicationContext, it) }
                }
                "https://drive.google.com/drive/folders/$id"
            } catch (e: NeedsReconnect) {
                DrivePrefs.setReconnect(applicationContext, true)
                null
            } catch (e: Exception) { null }
            runOnUiThread {
                if (link == null) {
                    Toast.makeText(this, "폴더를 확인하지 못했습니다. 드라이브 연결을 확인해 주세요.", Toast.LENGTH_LONG).show()
                    renderDrive()
                    return@runOnUiThread
                }
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("강의녹음 폴더", link))
                Toast.makeText(this, "링크를 복사했습니다. 드라이브 앱에서 이 폴더를 친구에게 편집자로 공유해 주세요.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun enterSharedLink() {
        val (til, et) = outlinedField("폴더 링크")
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(til, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("공유 폴더 링크 입력")
            .setMessage("친구가 보내 준 강의녹음 폴더 링크를 붙여 넣으세요. 앞으로 녹음과 전사본이 이 폴더에 저장됩니다.")
            .setView(wrap)
            .setPositiveButton("확인") { _, _ ->
                val id = Drive.folderIdFrom(et.text?.toString() ?: "")
                if (id == null) {
                    Toast.makeText(this, "링크를 알아보지 못했습니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Thread {
                    val name = try { Drive.folderName(Drive.token(applicationContext), id) } catch (e: Exception) { null }
                    runOnUiThread {
                        if (name == null) {
                            Toast.makeText(this, "폴더를 열 수 없습니다. 공유(편집자)가 됐는지, 드라이브 연결이 됐는지 확인해 주세요.", Toast.LENGTH_LONG).show()
                        } else {
                            DrivePrefs.setSharedRoot(this, id, name)
                            renderDrive()
                            SyncWorker.enqueue(this)
                            Toast.makeText(this, "\"$name\" 폴더에 저장합니다", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun outlined(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 13f
            setOnClickListener { onClick() }
        }

    private fun renderDrive() {
        driveBox.removeAllViews()
        val connected = DrivePrefs.connected(this)
        if (!connected) {
            driveBox.addView(Ui.sub(this, "녹음과 전사본을 내 드라이브의 강의녹음 폴더에 자동 저장하고, 같은 계정의 다른 기기와 목록을 맞춥니다.").apply {
                setPadding(0, 0, 0, dp(8))
            })
            driveBox.addView(MaterialButton(this).apply {
                text = "Google 드라이브 연결"
                textSize = 14f
                setOnClickListener { connectDrive() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        val mail = DrivePrefs.email(this).ifEmpty { "연결됨" }
        driveBox.addView(Ui.body(this, mail))
        val last = DrivePrefs.lastSync(this)
        val sub = when {
            DrivePrefs.needsReconnect(this) -> "다시 연결이 필요합니다. 아래 버튼을 눌러 주세요."
            last.isNotEmpty() -> "강의녹음 폴더에 자동 저장, 마지막 동기화 $last"
            else -> "강의녹음 폴더에 자동 저장"
        }
        driveBox.addView(Ui.sub(this, sub).apply {
            if (DrivePrefs.needsReconnect(this@SettingsActivity)) post { setTextColor(android.graphics.Color.parseColor("#C0392B")) }
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun outlined(label: String, onClick: () -> Unit) =
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 13f
                setOnClickListener { onClick() }
            }
        if (DrivePrefs.needsReconnect(this)) {
            row.addView(outlined("다시 연결") { connectDrive() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        } else {
            row.addView(outlined("지금 동기화") {
                SyncWorker.enqueue(this)
                Toast.makeText(this, "동기화를 시작했습니다", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
        row.addView(outlined("연결 해제") {
            MaterialAlertDialogBuilder(this)
                .setMessage("드라이브 연결을 해제할까요? 이미 올라간 파일은 드라이브에 그대로 남습니다.")
                .setPositiveButton("해제") { _, _ ->
                    DrivePrefs.setConnected(this, false)
                    renderDrive()
                }
                .setNegativeButton("취소", null)
                .show()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        driveBox.addView(row)
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "드라이브에 올라간 녹음은 기기에서 삭제"
            textSize = 14f
            isChecked = DrivePrefs.autoClean(this@SettingsActivity)
            setOnCheckedChangeListener { _, v ->
                DrivePrefs.setAutoClean(this@SettingsActivity, v)
                if (v) SyncWorker.enqueue(this@SettingsActivity)
            }
        }
        driveBox.addView(sw, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        driveBox.addView(Ui.sub(this, "전사가 끝나고 업로드가 확인된 녹음만 지웁니다. 재생할 때 다시 내려받습니다. 전사본은 그대로 남습니다."))

        // 친구와 같은 폴더 쓰기
        driveBox.addView(Ui.body(this, "같이 쓰는 폴더").apply { setPadding(0, dp(16), 0, 0) })
        val shared = DrivePrefs.sharedRoot(this)
        if (shared.isNotEmpty()) {
            driveBox.addView(Ui.sub(this, "친구가 공유한 폴더(${DrivePrefs.sharedRootName(this).ifEmpty { "이름 확인 중" }})에 저장 중입니다."))
            driveBox.addView(outlined("내 폴더로 되돌리기") {
                DrivePrefs.setSharedRoot(this, "", "")
                renderDrive()
                SyncWorker.enqueue(this)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        } else {
            driveBox.addView(Ui.sub(this, "내가 폴더 주인이면 링크를 복사해 드라이브에서 친구에게 편집자로 공유하세요. 친구는 그 링크를 아래 \"공유 폴더 링크 입력\"에 넣으면 됩니다."))
            val r2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            r2.addView(outlined("내 폴더 링크 복사") { copyMyFolderLink() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            r2.addView(outlined("공유 폴더 링크 입력") { enterSharedLink() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            driveBox.addView(r2)
        }
    }


    private fun dp(v: Int) = Ui.dp(this, v)

    private fun outlinedField(hint: String): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        val et = TextInputEditText(til.context)
        til.addView(et, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return til to et
    }

    private fun textButton(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 14f
            setAllCaps(false)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "설정"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        models.addAll(Prefs.models(this))
        schedule.addAll(Prefs.schedule(this))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }

        // 녹음자
        box.addView(Ui.section(this, "녹음자 이름"))
        val (nameLayout, ne) = outlinedField("이름 (예: 채현)")
        nameEdit = ne
        nameEdit.setText(Prefs.recorderName(this))
        box.addView(nameLayout)
        box.addView(Ui.sub(this, "제목 끝에 붙습니다. 예: 내분비학 2차_261026_7교시(15:26)_채현").apply {
            setPadding(dp(4), dp(4), 0, 0)
        })

        // 녹음 알림
        box.addView(Ui.section(this, "녹음 알림"))
        box.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "평일 수업 시작 전 녹음 알림"
            textSize = 14f
            isChecked = Prefs.remindersOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, v ->
                Prefs.setRemindersOn(this@SettingsActivity, v)
                Reminder.schedule(this@SettingsActivity)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(Ui.sub(this, "8:30, 9:30, 10:30, 11:30, 13:30, 14:30, 15:30, 16:30. 녹음 중이면 울리지 않습니다."))

        // 드라이브
        box.addView(Ui.section(this, "Google 드라이브"))
        driveBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(driveBox)

        // Gemini 키
        box.addView(Ui.section(this, "Gemini API 키 (기본 전사 엔진)"))
        val (keyLayout, ke) = outlinedField("API 키")
        keyEdit = ke
        keyLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        keyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyEdit.setText(Prefs.apiKey(this))
        box.addView(keyLayout)
        box.addView(Ui.sub(this, "aistudio.google.com 에서 발급. 키는 이 폰에만 저장됩니다.").apply {
            setPadding(dp(4), dp(4), 0, 0)
        })

        // Groq 키
        box.addView(Ui.section(this, "Groq API 키 (예비)"))
        val (groqLayout, ge) = outlinedField("API 키")
        groqEdit = ge
        groqLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        groqEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        groqEdit.setText(Prefs.groqKey(this))
        box.addView(groqLayout)
        box.addView(Ui.sub(this, "console.groq.com 에서 발급. Gemini가 모두 막히거나 결과가 의심될 때만 Whisper로 씁니다.").apply {
            setPadding(dp(4), dp(4), 0, 0)
        })

        // 모델
        box.addView(Ui.section(this, "Gemini 모델"))
        box.addView(Ui.sub(this, "위에서부터 사용. 바쁘면 다음 모델로, 전부 바쁘면 기다렸다가 다시 시도합니다.").apply {
            setPadding(0, 0, 0, dp(6))
        })
        modelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(modelBox)
        box.addView(textButton("+ 모델 추가") { editModel(-1) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        // 일정
        box.addView(Ui.section(this, "과목 일정"))
        box.addView(Ui.sub(this, "오늘 날짜에 해당하는 과목이 녹음 화면에서 기본으로 선택됩니다.").apply {
            setPadding(0, 0, 0, dp(6))
        })
        schedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(schedBox)
        box.addView(textButton("+ 과목 추가") { editSchedule(-1) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        box.addView(Ui.sub(this, "앱 버전: 빌드 ${ver.substringAfterLast('.')}").apply { setPadding(0, dp(24), 0, 0) })

        val scroll = ScrollView(this)
        scroll.addView(box)

        val save = MaterialButton(this).apply {
            text = "저장"
            textSize = 16f
            setOnClickListener { save() }
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(Ui.divider(this))
        root.addView(save, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply {
            setMargins(dp(16), dp(10), dp(16), dp(14))
        })
        setContentView(root)

        renderModels()
        renderSchedule()
        renderDrive()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun save() {
        val modelsText = models.joinToString("\n")
        val schedText = schedule.joinToString("\n") { "${it.subject},${it.start},${it.end}" }
        Prefs.setRecorderName(this, nameEdit.text?.toString() ?: "")
        Prefs.save(this, groqEdit.text?.toString() ?: "", keyEdit.text?.toString() ?: "", modelsText, schedText)
        Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ── 모델 목록 ─────────────────────────────
    private fun renderModels() {
        modelBox.removeAllViews()
        modelBox.addView(Ui.divider(this))
        models.forEachIndexed { i, m ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(Ui.sub(this, "${i + 1}").apply { minWidth = dp(20) })
            row.addView(Ui.body(this, m).apply { setOnClickListener { editModel(i) } },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(textButton("↑") { move(models, i, -1); renderModels() }.apply { isEnabled = i > 0 })
            row.addView(textButton("↓") { move(models, i, 1); renderModels() }.apply { isEnabled = i < models.size - 1 })
            row.addView(textButton("삭제") { models.removeAt(i); renderModels() })
            modelBox.addView(row)
            modelBox.addView(Ui.divider(this))
        }
        if (models.isEmpty()) {
            modelBox.addView(Ui.sub(this, "모델이 없습니다. 하나 이상 추가해 주세요.").apply { setPadding(0, dp(8), 0, dp(8)) })
        }
    }

    private fun <T> move(list: MutableList<T>, i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= list.size) return
        val t = list[i]
        list[i] = list[j]
        list[j] = t
    }

    private fun editModel(index: Int) {
        val (til, et) = outlinedField("모델 이름 (예: gemini-3.8-flash)")
        if (index >= 0) et.setText(models[index])
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(til, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (index >= 0) "모델 수정" else "모델 추가")
            .setView(wrap)
            .setPositiveButton("확인") { _, _ ->
                val v = et.text?.toString()?.trim() ?: ""
                if (v.isNotEmpty()) {
                    if (index >= 0) models[index] = v else models.add(v)
                    renderModels()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 과목 일정 ─────────────────────────────
    private fun renderSchedule() {
        schedBox.removeAllViews()
        schedBox.addView(Ui.divider(this))
        val today = LocalDate.now()
        schedule.sortBy { it.start }
        schedule.forEachIndexed { i, e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { editSchedule(i) }
            }
            val now = !today.isBefore(e.start) && !today.isAfter(e.end)
            row.addView(Ui.body(this, e.subject + if (now) " , 진행 중" else ""))
            row.addView(Ui.sub(this, "${e.start} ~ ${e.end}"))
            schedBox.addView(row)
            schedBox.addView(Ui.divider(this))
        }
        if (schedule.isEmpty()) {
            schedBox.addView(Ui.sub(this, "등록된 과목이 없습니다.").apply { setPadding(0, dp(8), 0, dp(8)) })
        }
    }

    private fun editSchedule(index: Int) {
        val old = if (index >= 0) schedule[index] else null
        var start = old?.start ?: LocalDate.now()
        var end = old?.end ?: LocalDate.now().plusWeeks(3)

        val (til, et) = outlinedField("과목 이름")
        if (old != null) et.setText(old.subject)

        fun dateBtn(label: String, get: () -> LocalDate, set: (LocalDate) -> Unit): MaterialButton {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.setAllCaps(false)
            fun refresh() { b.text = "$label  ${get()}" }
            refresh()
            b.setOnClickListener {
                val d = get()
                DatePickerDialog(this, { _, y, m, day ->
                    set(LocalDate.of(y, m + 1, day))
                    refresh()
                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            }
            return b
        }

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(til, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(dateBtn("시작", { start }, { start = it }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(dateBtn("종료", { end }, { end = it }), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(if (old != null) "과목 수정" else "과목 추가")
            .setView(wrap)
            .setPositiveButton("확인") { _, _ ->
                val name = et.text?.toString()?.trim()?.replace(",", " ") ?: ""
                if (name.isEmpty()) return@setPositiveButton
                val s = if (start.isAfter(end)) end else start
                val e = if (start.isAfter(end)) start else end
                val entry = ScheduleEntry(name, s, e)
                if (index >= 0) schedule[index] = entry else schedule.add(entry)
                renderSchedule()
            }
            .setNegativeButton("취소", null)
        if (index >= 0) {
            dlg.setNeutralButton("삭제") { _, _ ->
                schedule.removeAt(index)
                renderSchedule()
            }
        }
        dlg.show()
    }
}
