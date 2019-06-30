package com.google.firebase.ml.md.kotlin.helpers

import com.google.ar.sceneform.ux.BaseTransformableNode
import com.google.ar.sceneform.ux.RotationController
import com.google.ar.sceneform.ux.TransformationSystem
import com.google.ar.sceneform.ux.TranslationController

class DraggableRotableNode(transformationSystem: TransformationSystem) : BaseTransformableNode(transformationSystem) {
    /**
     * Returns the controller that translates this node using a drag gesture.
     */
    val translationController: TranslationController
    /**
     * Returns the controller that rotates this node using a twist gesture.
     */
    val rotationController: RotationController

    init {

        translationController = TranslationController(this, transformationSystem.dragRecognizer)
        addTransformationController(translationController)

        rotationController = RotationController(this, transformationSystem.twistRecognizer)
        addTransformationController(rotationController)
    }

}
