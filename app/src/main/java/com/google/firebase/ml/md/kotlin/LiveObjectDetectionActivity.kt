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

import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.View.GONE
import android.view.View.OnClickListener
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import com.google.common.base.Objects
import com.google.firebase.ml.md.R
import com.google.firebase.ml.md.kotlin.camera.FrameSource
import com.google.firebase.ml.md.kotlin.camera.GraphicOverlay
import com.google.firebase.ml.md.kotlin.camera.WorkflowModel
import com.google.firebase.ml.md.kotlin.camera.WorkflowModel.WorkflowState
import com.google.firebase.ml.md.kotlin.helpers.DraggableRotableNode
import com.google.firebase.ml.md.kotlin.objectdetection.ProminentObjectProcessor
import com.google.firebase.ml.md.kotlin.productsearch.SearchEngine
import com.google.firebase.ml.md.kotlin.settings.PreferenceUtils
import java.io.IOException

/** Demonstrates the object detection and visual search workflow using AR camera preview.  */
class LiveObjectDetectionActivity : AppCompatActivity(), OnClickListener {

    private lateinit var updateListener: Scene.OnUpdateListener
    private var done: Boolean = false
    private lateinit var frameSource: FrameSource
    private lateinit var preview: FrameLayout
    private lateinit var graphicOverlay: GraphicOverlay
    private var settingsButton: View? = null
    private var promptChip: Chip? = null
    private var promptChipAnimator: AnimatorSet? = null
    private var searchButton: ExtendedFloatingActionButton? = null
    private var searchButtonAnimator: AnimatorSet? = null
    private var searchProgressBar: ProgressBar? = null
    private lateinit var workflowModel: WorkflowModel
    private var currentWorkflowState: WorkflowState? = null
    private var searchEngine: SearchEngine? = null

    private lateinit var arFragment: ArFragment
    private var session: Session? = null
    private var sceneView: ArSceneView? = null
    private var shouldConfigureSession = false
    private var andyRenderable: ModelRenderable? = null
    private var luggageBB: ModelRenderable? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        searchEngine = SearchEngine(applicationContext)

        setContentView(R.layout.activity_live_object_kotlin)

        arFragment = supportFragmentManager.findFragmentById(R.id.ux_fragment) as ArFragment
        sceneView = arFragment.arSceneView


        preview = findViewById(R.id.camera_preview)
        graphicOverlay = findViewById<GraphicOverlay>(R.id.camera_preview_graphic_overlay).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
            frameSource = FrameSource(this)
        }

        promptChip = findViewById(R.id.bottom_prompt_chip)
        promptChipAnimator = (AnimatorInflater.loadAnimator(this, R.animator.bottom_prompt_chip_enter) as AnimatorSet).apply {
            setTarget(promptChip)
        }
        searchButton = findViewById<ExtendedFloatingActionButton>(R.id.product_search_button).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
        }
        searchButtonAnimator = (AnimatorInflater.loadAnimator(this, R.animator.search_button_enter) as AnimatorSet).apply {
            setTarget(searchButton)
        }
        searchProgressBar = findViewById(R.id.search_progress_bar)
        findViewById<View>(R.id.close_button).setOnClickListener(this)

        setUpWorkflowModel()


        // When you build a Renderable, Sceneform loads its resources in the background while returning
        // a CompletableFuture. Call thenAccept(), handle(), or check isDone() before calling get().
        ModelRenderable.builder()
                .setSource(this, R.raw.andy)
                .build()
                .thenAccept { renderable -> andyRenderable = renderable }
                .exceptionally {
                    val toast = Toast.makeText(this, "Unable to load andy renderable", Toast.LENGTH_LONG)
                    toast.setGravity(Gravity.CENTER, 0, 0)
                    toast.show()
                    null
                }

        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(Color.GREEN))
                .thenAccept {
                    luggageBB = ShapeFactory.makeCube(Vector3(.45f, .56f, .25f), Vector3(0f, 0f, -0.3f), it)
                }
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {

                session = Session(/* context = */this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: Exception) {
                message = "This device does not support AR"
                exception = e
            }

            if (message != null) {
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            shouldConfigureSession = true
        }

        if (shouldConfigureSession) {
            configureSession()
            shouldConfigureSession = false
            sceneView?.setupSession(session)
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session?.resume()
            sceneView?.resume()
        } catch (e: CameraNotAvailableException) {
            // In some cases (such as another camera app launching) the camera may be given to
            // a different app instead. Handle this properly by showing a message and recreate the
            // session at the next iteration.
            session = null
            return
        }



        workflowModel.markCameraFrozen()
        settingsButton?.isEnabled = true
        currentWorkflowState = WorkflowState.NOT_STARTED
        frameSource.run {
            setFrameProcessor(ProminentObjectProcessor(graphicOverlay, workflowModel))
            setSceneView(sceneView)
            setSession(session)
        }

        updateListener = Scene.OnUpdateListener {
            val frame = arFragment.arSceneView.arFrame
            if (frame != null) {
                for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
                    if (plane.trackingState == TrackingState.TRACKING) {
                        workflowModel.setWorkflowState(WorkflowState.DETECTING)
                    }
                }
            }
        }
        arFragment.arSceneView.scene.addOnUpdateListener(updateListener)
    }

    private fun configureSession() {
        val config = Config(session)
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        session?.configure(config)
    }


    override fun onPause() {
        super.onPause()

        if (session != null) {
            sceneView?.pause()
            session?.pause()
        }

        currentWorkflowState = WorkflowState.NOT_STARTED
        stopCameraPreview()
        frameSource.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        searchEngine?.shutdown()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.product_search_button -> {
                searchButton?.isEnabled = false
                workflowModel.onSearchButtonClicked()
            }
            R.id.close_button -> onBackPressed()
        }
    }

    private fun startCameraPreview() {
        if (!workflowModel.isCameraLive) {
            try {
                workflowModel.markCameraLive()
                frameSource.start(arFragment.arSceneView?.scene)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start camera preview!", e)
            }
        }
    }

    private fun stopCameraPreview() {
        if (workflowModel.isCameraLive) {
            workflowModel.markCameraFrozen()
            frameSource.stop()
        }
    }

    private fun setUpWorkflowModel() {
        workflowModel = ViewModelProviders.of(this).get(WorkflowModel::class.java).apply {


            // Observes the workflow state changes, if happens, update the overlay view indicators and
            // camera preview state.
            workflowState.observe(this@LiveObjectDetectionActivity, Observer { workflowState ->
                if (workflowState == null || Objects.equal(currentWorkflowState, workflowState)) {
                    return@Observer
                }
                currentWorkflowState = workflowState
                Log.d(TAG, "Current workflow state: " + workflowState.name)

                if (PreferenceUtils.isAutoSearchEnabled(this@LiveObjectDetectionActivity)) {
                    stateChangeInAutoSearchMode(workflowState)
                } else {
                    stateChangeInManualSearchMode(workflowState)
                }
            })

            // Observes changes on the object to search, if happens, fire product search request.
            objectToSearch.observe(this@LiveObjectDetectionActivity, Observer { detectObject ->
                searchEngine!!.search(detectObject) { detectedObject, products -> workflowModel.onSearchCompleted(detectedObject, products) }
            })

            // Observes changes on the object that has search completed, if happens, show the bottom sheet
            // to present search result.
            searchedObject.observe(this@LiveObjectDetectionActivity, Observer { nullableSearchedObject ->
                val searchedObject = nullableSearchedObject ?: return@Observer
                val box = graphicOverlay.translateRect(searchedObject.boundingBox)
                Log.d(TAG, "Found $box")
                graphicOverlay.clear()
                graphicOverlay.visibility = GONE

                graphicOverlay.let {
                    val center = PointF(
                            ((box.left + box.right) / 2f),
                            ((box.bottom))
                    )
                    val frame = arFragment.arSceneView?.arFrame
                    if (frame != null) {
                        val hits = frame.hitTest(center.x, center.y)
                        for (hit in hits) {
                            val trackable = hit.trackable
                            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) && !done) {

                                // Create the Anchor.
                                val anchor = hit.createAnchor()
                                val anchorNode = AnchorNode(anchor)
                                anchorNode.setParent(arFragment.arSceneView?.scene)

                                // Create the transformable and add it to the anchor.
                                DraggableRotableNode(arFragment.transformationSystem).apply {
                                    setParent(anchorNode)
                                    renderable = luggageBB
                                    select()
                                }
                                done = true
                                break
                            }
                        }
                    }

                }
            })
        }
    }

    private fun stateChangeInAutoSearchMode(workflowState: WorkflowState) {
        val wasPromptChipGone = promptChip!!.visibility == GONE

        searchButton?.visibility = GONE
        searchProgressBar?.visibility = GONE
        when (workflowState) {
            WorkflowState.DETECTING, WorkflowState.DETECTED, WorkflowState.CONFIRMING -> {
                if (workflowState == WorkflowState.DETECTING) {
                    arFragment.arSceneView.scene.removeOnUpdateListener(updateListener)
                }
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(
                        if (workflowState == WorkflowState.CONFIRMING)
                            R.string.prompt_hold_camera_steady
                        else
                            R.string.prompt_point_at_an_object)
                startCameraPreview()
            }
            WorkflowState.CONFIRMED -> {
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(R.string.prompt_searching)
                stopCameraPreview()
            }
            WorkflowState.SEARCHING -> {
                searchProgressBar?.visibility = View.VISIBLE
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(R.string.prompt_searching)
                stopCameraPreview()
            }
            WorkflowState.SEARCHED -> {
                promptChip?.visibility = GONE
                stopCameraPreview()
            }
            else -> promptChip?.visibility = GONE
        }

        val shouldPlayPromptChipEnteringAnimation = wasPromptChipGone && promptChip?.visibility == View.VISIBLE
        if (shouldPlayPromptChipEnteringAnimation && promptChipAnimator?.isRunning == false) {
            promptChipAnimator?.start()
        }
    }

    private fun stateChangeInManualSearchMode(workflowState: WorkflowState) {
        val wasPromptChipGone = promptChip?.visibility == GONE
        val wasSearchButtonGone = searchButton?.visibility == GONE

        searchProgressBar?.visibility = GONE
        when (workflowState) {
            WorkflowState.DETECTING, WorkflowState.DETECTED, WorkflowState.CONFIRMING -> {
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(R.string.prompt_point_at_an_object)
                searchButton?.visibility = GONE
                startCameraPreview()
            }
            WorkflowState.CONFIRMED -> {
                promptChip?.visibility = GONE
                searchButton?.visibility = View.VISIBLE
                searchButton?.isEnabled = true
                searchButton?.setBackgroundColor(Color.WHITE)
                startCameraPreview()
            }
            WorkflowState.SEARCHING -> {
                promptChip?.visibility = GONE
                searchButton?.visibility = View.VISIBLE
                searchButton?.isEnabled = false
                searchButton?.setBackgroundColor(Color.GRAY)
                searchProgressBar!!.visibility = View.VISIBLE
                stopCameraPreview()
            }
            WorkflowState.SEARCHED -> {
                promptChip?.visibility = GONE
                searchButton?.visibility = GONE
                stopCameraPreview()
            }
            else -> {
                promptChip?.visibility = GONE
                searchButton?.visibility = GONE
            }
        }

        val shouldPlayPromptChipEnteringAnimation = wasPromptChipGone && promptChip?.visibility == View.VISIBLE
        promptChipAnimator?.let {
            if (shouldPlayPromptChipEnteringAnimation && !it.isRunning) it.start()
        }

        val shouldPlaySearchButtonEnteringAnimation = wasSearchButtonGone && searchButton?.visibility == View.VISIBLE
        searchButtonAnimator?.let {
            if (shouldPlaySearchButtonEnteringAnimation && !it.isRunning) it.start()
        }
    }


    companion object {
        private const val TAG = "LiveObjectActivity"
    }
}
