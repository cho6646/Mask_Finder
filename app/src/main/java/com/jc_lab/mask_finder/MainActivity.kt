package com.jc_lab.mask_finder

import android.graphics.Color.*
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.UiThread
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.toolbox.Volley
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.*
import com.naver.maps.map.CameraUpdate.*
import com.naver.maps.map.overlay.*
import com.naver.maps.map.util.FusedLocationSource
import com.naver.maps.map.util.MarkerIcons
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var queue: RequestQueue
    private lateinit var locationSource: FusedLocationSource
    private lateinit var naverMap: NaverMap
    private lateinit var gpsButton: ImageButton
    private lateinit var searchButton: Button

    private var mMeter: Int = 500
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var currentBearing: Float = 0.0f
    private var gpsFollow: Boolean = true
    private var maskInfoCollected: Boolean = false
    private var selectedInfoWindow: InfoWindow = InfoWindow()
    private var storeDatas = mutableListOf<StoreData>()
    private var markers = mutableListOf<Marker>()
    private var infoWindows = mutableListOf<InfoWindow>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map) as MapFragment?
            ?: MapFragment.newInstance().also{
            fm.beginTransaction().add(R.id.map, it).commit()
        }

        gpsButton = findViewById(R.id.gps_button)
        searchButton = findViewById(R.id.search_button)

        queue = Volley.newRequestQueue(this)

        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE)

        mapFragment.getMapAsync(this)
    }

    @UiThread
    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
        naverMap.locationSource = locationSource
        naverMap.locationTrackingMode = LocationTrackingMode.Follow

        naverMap.addOnLocationChangeListener { location ->
            currentLat = location.latitude
            currentLng = location.longitude
            currentBearing = location.bearing
            if( gpsFollow )
            {
                val cameraUpdate = scrollTo(LatLng(location.latitude, location.longitude))
                naverMap.moveCamera(cameraUpdate)
                if( !maskInfoCollected )
                {
                    getMaskInfo(currentLat, currentLng, mMeter)
                    maskInfoCollected = true
                }
            }
        }

        naverMap.addOnCameraChangeListener { reason, animated ->
            if(reason == REASON_GESTURE) gpsFollow = false
        }

        naverMap.addOnCameraIdleListener {
            mMeter = zoomSet(naverMap.cameraPosition.zoom).toInt()
        }

        naverMap.setOnMapClickListener { point, coord ->
            selectedInfoWindow.close()
        }

        gpsButton.setOnClickListener{ view ->
            naverMap.moveCamera(toCameraPosition(CameraPosition(LatLng(currentLat, currentLng), 14.0)).animate(CameraAnimation.Easing))
            gpsFollow = true
            maskInfoCollected = false
        }

        searchButton.setOnClickListener{ view ->
            markerAndInfoWindowClear()
            val cameraPositionTarget = naverMap.cameraPosition.target
            getMaskInfo(cameraPositionTarget.latitude, cameraPositionTarget.longitude, mMeter)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions,
                grantResults)) {
            Log.d("activated", "Response: ${locationSource.isActivated}")
            if (!locationSource.isActivated) { // 권한 거부됨
                naverMap.locationTrackingMode = LocationTrackingMode.None
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun getMaskInfo(lat: Double?, lng: Double?, meter: Int){
        if(lat == null || lng == null) return
        if(lat < 33.0 || lat > 43.0) return
        if(lng < 124.0 || lng > 132.0) return
        if(meter <= 0 || meter > 5000) return

        val url = "https://8oi9s0nnth.apigw.ntruss.com/corona19-masks/v1/storesByGeo/json?lat=${lat}&lng=${lng}&m=${meter}"

        // Request a string response from the provided URL.
        val stringRequest = VolleyUTF8EncodingStringRequest( Request.Method.GET, url,
            Response.Listener<String> { response ->
                storeDatas.clear()
                val jsonDataObject = JSONObject(response)
                val cnt = jsonDataObject.getInt("count")
                val stores = jsonDataObject.getJSONArray("stores")
                for(i in 0 until cnt)
                {
                    val item = stores.getJSONObject(i)

                    storeDatas.add(StoreData(item.getString("code"),
                        item.getString("addr"),
                        item.getString("created_at"),
                        item.getString("lat").toDouble(),
                        item.getString("lng").toDouble(),
                        item.getString("name"),
                        item.getString("remain_stat"),
                        item.getString("stock_at"),
                        item.getString("type")))
                }
                markerAndInfoWindowSetup(cnt)

                Toast.makeText(this, "현재 위치, 경도: ${floor(lat*100)/100}, 위도: ${floor(lng*100)/100}에서 $cnt 곳의 마스크 판매소를 찾았습니다", Toast.LENGTH_LONG).show()
            },
            Response.ErrorListener { Log.d("error","That didn't work!") })

        // Add the request to the RequestQueue.
        queue.add(stringRequest)
    }

    private fun markerAndInfoWindowSetup(mCnt: Int){
        if(mCnt == 0)
            return
        val executor: ExecutorService = Executors.newFixedThreadPool(mCnt)
        val handler = Handler(Looper.getMainLooper())

        executor.execute {
            for(j in 0 until mCnt)
            {
                val storeDataItem = storeDatas.get(j)
                val marker = putInfoInMarker(storeDataItem)

                var type = ""
                if(storeDataItem.type=="01") type = "약국"
                else if(storeDataItem.type == "02") type = "농협 하나로 마트"
                else type = "우체국"

                markers.add(marker)
                val infoWindow = InfoWindow()
                infoWindow.adapter = object : InfoWindow.DefaultTextAdapter(this) {
                    override fun getText(infoWindow: InfoWindow): CharSequence {
                        return "이름: ${storeDataItem.name}\n종류: $type"
                    }
                }
                infoWindows.add(infoWindow)
                marker.setOnClickListener{overlay ->
                    val marker = overlay as Marker

                    if (marker.infoWindow == null) {
                        // 현재 마커에 정보 창이 열려있지 않을 경우 엶
                        selectedInfoWindow.close()
                        infoWindow.open(marker)
                        selectedInfoWindow = infoWindow
                    } else {
                        // 이미 현재 마커에 정보 창이 열려있을 경우 닫음
                        infoWindow.close()
                        selectedInfoWindow = InfoWindow()
                    }

                    true
                }
            }
            handler.post{
                markers.forEach{ marker ->
                    marker.map = naverMap
                }
            }
        }

        executor.shutdown()
    }

    private fun markerAndInfoWindowClear(){
        markers.forEach{ marker ->
            marker.map = null
        }
        selectedInfoWindow.close()
        markers.clear()
        infoWindows.clear()
    }

    private fun putInfoInMarker(storeDataItem: StoreData) : Marker
    {
        return Marker().apply {
            position = LatLng(storeDataItem.lat, storeDataItem.lng)
            icon = MarkerIcons.BLACK
            width = 100
            height = 130
            captionText = storeDataItem.name
            isHideCollidedSymbols = true
            isHideCollidedCaptions = true
            isHideCollidedMarkers = true

            if(storeDataItem.remain_stat == "plenty")
            {
                iconTintColor = GREEN
                zIndex = 100
            }
            else if(storeDataItem.remain_stat == "some")
            {
                iconTintColor = YELLOW
                zIndex = 0
            }
            else if(storeDataItem.remain_stat == "few")
            {
                iconTintColor = RED
                zIndex = -10
            }
            else
            {
                iconTintColor = GRAY
                zIndex = -100
            }
        }
    }

    private fun zoomSet(zoom : Double) : Double
    {
        var zoomToChange = min(zoom, 14.0)
        zoomToChange = max(zoomToChange, 9.0)
        return 500 + 4500*(14-zoomToChange)/5
    }

    companion object{
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
    }
}

