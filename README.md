# ARCore-Sceneform-ML Kit Showcase App with Material Design

This app demonstrates how to build an end-to-end user experience with 
[Google ML Kit APIs](https://developers.google.com/ml-kit) [Google ARcore](https://developers.google.com/ar/) and following the
[new Material for ML design guidelines](https://material.io/collections/machine-learning/)

## Steps to run the app

* Clone this repo locally
  ```
  git clone https://github.com/joaobiriba/arcoremlkit
  ```
* [Create a Firebase project in the Firebase console, if you don't already have one](https://firebase.google.com/docs/android/setup)
* Add a new Android app into your Firebase project with package name com.google.firebase.ml.md
* Download the config file (google-services.json) from the new added app and move it into the module folder (i.e. [app/](./app/))
* Build and run it on an Android device

## How to use the app

This app supports only one usage scenarios: Live Camera from ARCore+Sceneform and MLKit Object Detection with Vision API.
