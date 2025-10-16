

package com.example.akdk

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

object ActivationDialog {

    private var dialog: Dialog? = null
    private lateinit var inputField: EditText
    private lateinit var rememberCheck: CheckBox
    private lateinit var messageText: TextView
    private lateinit var prefs: SharedPreferences

    private const val GITHUB_JSON_URL =
        "https://raw.githubusercontent.com/Git1503/upn-1/refs/heads/main/temp.json"

    fun show(activity: Activity) {
        prefs = activity.getSharedPreferences("ActivationPrefs", Context.MODE_PRIVATE)

        // ---------- Container ----------
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 20))
        }

        // ---------- Title ----------
         //val title = TextView(activity).apply {
           // text = "Activation"
            //textSize = 22f
            //setTextColor(Color.BLACK)
            //gravity = Gravity.CENTER
            //setPadding(0, dp(activity, 8), 0, dp(activity, 16))
       // }

        // ---------- Input Field ----------
        inputField = EditText(activity).apply {
            hint = "Enter your key"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFEFEF"))
                cornerRadius = dp(activity, 8).toFloat()
            }
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            setText(prefs.getString("activation_key", ""))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(activity, 10)
            }
        }

        // ---------- Remember Checkbox ----------
        rememberCheck = CheckBox(activity).apply {
            text = "Remember this device"
            isChecked = prefs.getBoolean("remember_key", false)
            setTextColor(Color.DKGRAY)
        }

        // ---------- Message Text ----------
        messageText = TextView(activity).apply {
            visibility = View.GONE
            setTextColor(Color.RED)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 8), 0, 0)
        }

        // ---------- Buttons ----------
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val copyBtn = makeButton(activity, "Copy ID", Color.parseColor("#2196F3")).apply {
            setOnClickListener {
                val deviceId =
                    Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID)
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                Toast.makeText(activity, "Device ID copied!", Toast.LENGTH_SHORT).show()
                bounceAnim(this)
            }
        }

        val activateBtn = makeButton(activity, "Activate", Color.parseColor("#4CAF50")).apply {
            setOnClickListener {
                val key = inputField.text.toString().trim()
                if (key.isEmpty()) {
                    showMessage("Please enter a key")
                    return@setOnClickListener
                }

                showMessage("Verifying...") // Loading feedback
                verifyWithGitHub(activity, key)
                bounceAnim(this)
            }
        }

        buttons.addView(copyBtn)
        buttons.addView(activateBtn)

        // ---------- Assemble Container ----------
        //container.addView(title)
        container.addView(inputField)
        container.addView(rememberCheck)
        container.addView(messageText)
        container.addView(buttons)

        val bg = GradientDrawable().apply {
            cornerRadius = dp(activity, 16).toFloat()
            setColor(Color.WHITE)
        }

        val builder = AlertDialog.Builder(activity).setView(container)
        dialog = builder.create().apply {
            window?.setBackgroundDrawable(bg)
            show()
        }

        fadeScaleIn(container)
    }

    // ---------------- Verify with GitHub ----------------
    private fun verifyWithGitHub(activity: Activity, key: String) {
    val deviceId = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID)
    val client = OkHttpClient()
    val request = Request.Builder().url(GITHUB_JSON_URL).build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            activity.runOnUiThread { showMessage("Failed to fetch activation data") }
        }

        override fun onResponse(call: Call, response: Response) {
            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                activity.runOnUiThread { showMessage("Invalid activation data") }
                return
            }

            try {
                val jsonArray: JSONArray = when {
                    body.trim().startsWith("[") -> {
                        JSONArray(body) // JSON array directly
                    }
                    body.trim().startsWith("{") -> {
                        // JSON object containing an array
                        val obj = JSONObject(body)
                        // adjust key here if your JSON has a specific field, e.g., "devices"
                        obj.getJSONArray("devices")
                    }
                    else -> {
                        activity.runOnUiThread { showMessage("Malformed activation data") }
                        return
                    }
                }

                val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                val today = Date()
                var success = false

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val allowedDeviceId = obj.getString("deviceid")
                    val validKey = obj.getString("key")
                    val expiry = obj.getString("expiry")
                    val allowOffline = obj.getBoolean("allowoffline")
                    val expiryDate = sdf.parse(expiry)

                    if (deviceId == allowedDeviceId && key == validKey) {
                        success = when {
                            today.after(expiryDate) -> {
                                activity.runOnUiThread { showMessage("Key expired") }
                                false
                            }
                            !allowOffline -> {
                                activity.runOnUiThread { showMessage("Activation not allowed offline") }
                                false
                            }
                            else -> true
                        }
                        break
                    }
                }

                activity.runOnUiThread {
                    if (success) {
                        saveKey(key, rememberCheck.isChecked)
                        showMessage("Activation successful!")
                        dialog?.dismiss()
                    } else if (!success) {
                        showMessage("Invalid device/key")
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread { showMessage("Error parsing activation data") }
            }
        }
    })
}

    // ---------------- Helpers ----------------
    private fun makeButton(context: Context, text: String, color: Int): Button =
        Button(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            setAllCaps(false)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(context, 8).toFloat()
            }
            val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            params.setMargins(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            layoutParams = params
        }

    private fun saveKey(key: String, remember: Boolean) {
        prefs.edit().apply {
            putString("activation_key", key)
            putBoolean("remember_key", remember)
            apply()
        }
    }

    private fun showMessage(msg: String) {
        messageText.apply {
            text = msg
            visibility = View.VISIBLE
        }
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private fun fadeScaleIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f

        val fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f)
        val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.8f, 1f)
        val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.8f, 1f)

        AnimatorSet().apply {
            playTogether(fade, scaleX, scaleY)
            duration = 400
            start()
        }
    }

    private fun bounceAnim(view: View) {
        val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.95f, 1f)
        val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.95f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 200
            start()
        }
    }
}
