package com.hussein.myapplication


import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.hussein.myapplication.databinding.ActivityMainBinding
import com.hussein.myapplication.databinding.ItemCalloutViewBinding
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.dsl.generated.interpolate
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.flyTo
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.overlay.mapboxOverlay
import com.mapbox.maps.viewannotation.ViewAnnotationManager
import com.mapbox.maps.viewannotation.ViewAnnotationUpdateMode
import com.mapbox.maps.viewannotation.viewAnnotationOptions

import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.item_callout_view.*
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity(), PermissionsListener, OnMapClickListener {

    private lateinit var mapView: MapView
    private lateinit var mapboxMap: MapboxMap
    private lateinit var viewAnnotationManager: ViewAnnotationManager
    private val viewAnnotationViews = mutableListOf<View>()

    private lateinit var locationPermissionHelper: LocationPermissionHelper
    private lateinit var permissionsManager: PermissionsManager
    private lateinit var locationEngine: LocationEngine
    private lateinit var callback: LocationListeningCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapView = MapView(this)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        locationEngine = LocationEngineProvider.getBestLocationEngine(this)
        callback = LocationListeningCallback(this)
        locationPermissionHelper = LocationPermissionHelper(WeakReference(this))
        locationPermissionHelper.checkPermissions {
            onMapReady()
            viewAnnotationManager = binding.mapView.viewAnnotationManager
            mapboxMap = binding.mapView.getMapboxMap().apply {
                loadStyleUri(Style.MAPBOX_STREETS) {
                    addOnMapClickListener(this@MainActivity)
                    binding.fabStyleToggle.setOnClickListener {
                        when (getStyle()?.styleURI) {
                            Style.MAPBOX_STREETS -> loadStyleUri(Style.SATELLITE_STREETS)
                            Style.SATELLITE_STREETS -> loadStyleUri(Style.MAPBOX_STREETS)
                        }
                    }
                    Toast.makeText(this@MainActivity,
                        STARTUP_TEXT,
                        Toast.LENGTH_LONG).show()
                }
            }

            binding.fabReframe.setOnClickListener {
                if (viewAnnotationViews.isNotEmpty()) {
                    val cameraOptions =
                        viewAnnotationManager.cameraForAnnotations(viewAnnotationViews)
                    cameraOptions?.let {
                        mapboxMap.flyTo(it)
                    }
                } else {
                    Toast.makeText(this@MainActivity,
                        ADD_VIEW_ANNOTATION_TEXT,
                        Toast.LENGTH_LONG).show()
                }
            }
        }


    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_view_annotation, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_view_annotation_fixed_delay -> {
                viewAnnotationManager.setViewAnnotationUpdateMode(ViewAnnotationUpdateMode.MAP_FIXED_DELAY)
                true
            }
            R.id.action_view_annotation_map_synchronized -> {
                viewAnnotationManager.setViewAnnotationUpdateMode(ViewAnnotationUpdateMode.MAP_SYNCHRONIZED)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onMapClick(point: Point): Boolean {
        addViewAnnotation(point)
        return true
    }

    @SuppressLint("SetTextI18n")
    private fun addViewAnnotation(point: Point) {
        val viewAnnotation = viewAnnotationManager.addViewAnnotation(
            resId = R.layout.item_callout_view,
            options = viewAnnotationOptions {
                geometry(point)
                allowOverlap(true)
            }
        )
        viewAnnotationViews.add(viewAnnotation)
        ItemCalloutViewBinding.bind(viewAnnotation).apply {
            textNativeView.text = "lat=%.2f\nlon=%.2f".format(point.latitude(), point.longitude())
            closeNativeView.setOnClickListener {
                viewAnnotationManager.removeViewAnnotation(viewAnnotation)
                viewAnnotationViews.remove(viewAnnotation)
            }
            selectButton.setOnClickListener { b ->
                val button = b as Button
                val isSelected = button.text.toString().equals("SELECT", true)
                val pxDelta = if (isSelected) SELECTED_ADD_COEF_PX else -SELECTED_ADD_COEF_PX
                button.text = if (isSelected) "DESELECT" else "SELECT"
                viewAnnotationManager.updateViewAnnotation(
                    viewAnnotation,
                    viewAnnotationOptions {
                        selected(isSelected)
                    }
                )
                (button.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    bottomMargin += pxDelta
                    rightMargin += pxDelta
                    leftMargin += pxDelta
                }
                button.requestLayout()
            }
        }
    }


    private companion object {
        const val SELECTED_ADD_COEF_PX = 25
        const val STARTUP_TEXT = "Click on a map to add a view annotation."
        const val ADD_VIEW_ANNOTATION_TEXT = "Add view annotations to re-frame map camera"
    }

    private fun onMapReady() {
        mapView.getMapboxMap().setCamera(
            CameraOptions.Builder()
                .zoom(14.0)
                .build()
        )
        mapView.getMapboxMap().loadStyleUri(
            Style.MAPBOX_STREETS
        ) {
            initLocationComponent()
            setupGesturesListener()
        }

    }

    private fun setupGesturesListener() {
        mapView.gestures.addOnMoveListener(onMoveListener)
    }

    private fun initLocationComponent() {
        val locationComponentPlugin = mapView.location
        locationComponentPlugin.updateSettings {
            this.enabled = true
            this.locationPuck = LocationPuck2D(
                bearingImage = AppCompatResources.getDrawable(
                    this@MainActivity,
                    R.drawable.mapbox_user_puck_icon,
                ),
                shadowImage = AppCompatResources.getDrawable(
                    this@MainActivity,
                    R.drawable.mapbox_user_icon_shadow,
                ),
                scaleExpression = interpolate {
                    linear()
                    zoom()
                    stop {
                        literal(0.0)
                        literal(0.6)
                    }
                    stop {
                        literal(20.0)
                        literal(1.0)
                    }
                }.toJson()
            )
        }
        locationComponentPlugin.addOnIndicatorPositionChangedListener(
            onIndicatorPositionChangedListener)
        locationComponentPlugin.addOnIndicatorBearingChangedListener(
            onIndicatorBearingChangedListener)
    }

    private fun onCameraTrackingDismissed() {
        Toast.makeText(this, "onCameraTrackingDismissed", Toast.LENGTH_SHORT).show()
        mapView.location
            .removeOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
        mapView.location
            .removeOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        mapView.gestures.removeOnMoveListener(onMoveListener)
    }

    private val onIndicatorBearingChangedListener = OnIndicatorBearingChangedListener {
        mapView.getMapboxMap().setCamera(CameraOptions.Builder().bearing(it).build())
    }

    private val onIndicatorPositionChangedListener = OnIndicatorPositionChangedListener {
        mapView.getMapboxMap().setCamera(CameraOptions.Builder().center(it).build())
        mapView.gestures.focalPoint = mapView.getMapboxMap().pixelForCoordinate(it)
    }

    private val onMoveListener = object : OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) {
            onCameraTrackingDismissed()
        }

        override fun onMove(detector: MoveGestureDetector): Boolean {
            return false
        }

        override fun onMoveEnd(detector: MoveGestureDetector) {}
    }


    @SuppressLint("MissingPermission")
    fun getLocation() {
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            val DEFAULT_INTERVAL_IN_MILLISECONDS = 1000L
            val DEFAULT_MAX_WAIT_TIME = DEFAULT_INTERVAL_IN_MILLISECONDS * 5
            locationEngine = LocationEngineProvider.getBestLocationEngine(this)
            var request = LocationEngineRequest.Builder(DEFAULT_INTERVAL_IN_MILLISECONDS)
                .setPriority(LocationEngineRequest.PRIORITY_NO_POWER)
                .setMaxWaitTime(DEFAULT_MAX_WAIT_TIME)
                .build()
            locationEngine.requestLocationUpdates(request, callback, mainLooper)
            locationEngine.getLastLocation(callback)


        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager.requestLocationPermissions(this)
        }
    }

    override fun onExplanationNeeded(permissionsToExplain: MutableList<String>?) {
        Toast.makeText(this@MainActivity, "Need Location Permission ", Toast.LENGTH_SHORT).show()
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {

            getLocation()

        } else {

            // User denied the permission

        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.location
            .removeOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        mapView.location
            .removeOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
        mapView.gestures.removeOnMoveListener(onMoveListener)
        mapView.onDestroy()
    }

    override fun onStop() {
        super.onStop()

        locationEngine?.removeLocationUpdates(callback)

        mapView.onStop()
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationPermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }


}