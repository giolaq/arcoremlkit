/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase.ml.md.kotlin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.ml.md.R

/** Entry activity to select the detection mode.  */
class MainActivity : AppCompatActivity() {

    private enum class DetectionMode(val titleResId: Int, val subtitleResId: Int) {
        ODT_LIVE(R.string.mode_odt_live_title, R.string.mode_odt_live_subtitle),
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)

        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        setContentView(R.layout.activity_main)
        findViewById<RecyclerView>(R.id.mode_recycler_view).apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ModeItemAdapter(DetectionMode.values())
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Utils.allPermissionsGranted(this)) {
            Utils.requestRuntimePermissions(this)
        }
    }

    private inner class ModeItemAdapter internal constructor(private val detectionModes: Array<DetectionMode>) : RecyclerView.Adapter<ModeItemAdapter.ModeItemViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModeItemViewHolder {
            return ModeItemViewHolder(
                    LayoutInflater.from(parent.context)
                            .inflate(R.layout.detection_mode_item, parent, false
                            )
            )
        }

        override fun onBindViewHolder(modeItemViewHolder: ModeItemViewHolder, position: Int) = modeItemViewHolder.bindDetectionMode(detectionModes[position])

        override fun getItemCount(): Int = detectionModes.size


        private inner class ModeItemViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {

            private val titleView: TextView = view.findViewById(R.id.mode_title)
            private val subtitleView: TextView = view.findViewById(R.id.mode_subtitle)

            internal fun bindDetectionMode(detectionMode: DetectionMode) {
                titleView.setText(detectionMode.titleResId)
                subtitleView.setText(detectionMode.subtitleResId)
                itemView.setOnClickListener {
                    val activity = this@MainActivity
                    when (detectionMode) {
                        DetectionMode.ODT_LIVE -> activity.startActivity(Intent(activity, LiveObjectDetectionActivity::class.java))
                    }
                }
            }
        }
    }
}
