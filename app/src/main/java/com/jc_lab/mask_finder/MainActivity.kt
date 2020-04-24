package com.jc_lab.mask_finder

import android.graphics.Color.*
import android.icu.text.IDNA
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var queue: RequestQueue
    private lateinit var locationSource: FusedLocationSource
    private lateinit var naverMap: NaverMap
    private var mLat: Double = 0.0
    private var mLng: Double = 0.0
    private var mMeter: Int = 500
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var currentBearing: Float = 0.0f
    private var gpsFollow: Boolean = true
    private var maskInfoCollected: Boolean = false
    private var selectedInfoWindow: InfoWindow = InfoWindow()
    private var storeDatas = mutableListOf<StoreData>()
//    private lateinit var postOffice: OverlayImage
//    private lateinit var nongHyup: OverlayImage
//    private lateinit var drugStore: OverlayImage


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map) as MapFragment?
            ?: MapFragment.newInstance().also{
            fm.beginTransaction().add(R.id.map, it).commit()
        }

        queue = Volley.newRequestQueue(this)

        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE)

//        postOffice = OverlayImage.fromResource(R.drawable.post_office)
//        drugStore = OverlayImage.fromResource(R.drawable.drug_store)
//        nongHyup = OverlayImage.fromResource(R.drawable.nong_hyup)

        mapFragment.getMapAsync(this)
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
        if(lat == null || lng == null)
            return
        if(lat < 33.0 || lat >43.0)
            return
        if(lng < 124.0 || lng > 132.0)
            return
        if(meter <= 0 || meter > 5000)
            return

        val url = "https://8oi9s0nnth.apigw.ntruss.com/corona19-masks/v1/storesByGeo/json?lat=${lat}&lng=${lng}&m=${meter}"

        // Request a string response from the provided URL.
        val stringRequest = VolleyUTF8EncodingStringRequest( Request.Method.GET, url,
            Response.Listener<String> { response ->
                // Display the first 500 characters of the response string.
//                textView.text = "Response is: ${response.substring(0, 500)}"
//                Log.d("response", "Response: $response")
                storeDatas.clear()
                val jsonDataObject = JSONObject(response)
                val cnt = jsonDataObject.getInt("count")
                val stores = jsonDataObject.getJSONArray("stores")
                for(i in 0 until cnt)
                {
                    val item = stores.getJSONObject(i)
                    val itemAddr = item.getString("addr")
                    val itemCreatedAt = item.getString("created_at")
                    val itemLat = item.getString("lat")
                    val itemLng = item.getString("lng")
                    val itemName = item.getString("name")
                    val itemRemainStat = item.getString("remain_stat")
                    val itemStockAt = item.getString("stock_at")
                    val itemType = item.getString("type")

                    storeDatas.add(StoreData(itemAddr, itemCreatedAt, itemLat.toDouble(), itemLng.toDouble(), itemName, itemRemainStat, itemStockAt, itemType))
                }
                markerAndInfoWindowSetup(cnt)
            },
            Response.ErrorListener { Log.d("error","That didn't work!") })

        // Add the request to the RequestQueue.
        queue.add(stringRequest)
    }

    private fun markerAndInfoWindowSetup(mCnt: Int){
        val executor: Executor = Executors.newFixedThreadPool(mCnt)
        val handler = Handler(Looper.getMainLooper())

        executor.execute {

            val markers = mutableListOf<Marker>()
            val infoWindows = mutableListOf<InfoWindow>()
            var type = ""
            for(j in 0 until mCnt)
            {
                val storeDataItem = storeDatas.get(j)
                val marker = Marker().apply{
                    position = LatLng(storeDataItem.lat, storeDataItem.lng)
                    icon = MarkerIcons.BLACK
                    width = 100
                    height = 130
                    captionText = storeDataItem.name
                    isHideCollidedSymbols = true
                    isHideCollidedCaptions = true
                    isHideCollidedMarkers = true

//                    captionAligns = arrayOf(Align.Right)
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
                    if(storeDataItem.type=="01")
                    {
                        type = "약국"
                    }
                    else if(storeDataItem.type == "02")
                    {
                        type = "농협 하나로 마트"
                    }
                    else
                    {
                        type = "우체국"
                    }

                }
                markers += marker
                val infoWindow = InfoWindow()
                infoWindow.adapter = object : InfoWindow.DefaultTextAdapter(this) {
                    override fun getText(infoWindow: InfoWindow): CharSequence {
                        return "이름: ${storeDataItem.name}\n종류: $type"
                    }
                }
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
    }

    @UiThread
    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
//        val options = NaverMapOptions()
//            .camera(CameraPosition(LatLng(35.1798159, 129.0750222), 8.0))
//            .mapType(NaverMap.MapType.Terrain)
        // current location of camera
        var cameraPosition = naverMap.cameraPosition
        naverMap.locationSource = locationSource
        naverMap.locationTrackingMode = LocationTrackingMode.Follow
        val locationOverlay = naverMap.locationOverlay

        naverMap.addOnLocationChangeListener { location ->
            currentLat = location.latitude
            currentLng = location.longitude
            currentBearing = location.bearing
            if( gpsFollow )
            {
                val cameraUpdate = CameraUpdate.scrollTo(LatLng(location.latitude, location.longitude))
                naverMap.moveCamera(cameraUpdate)
//                locationOverlay.bearing = currentBearing
                if( !maskInfoCollected )
                {
                    getMaskInfo(currentLat, currentLng, mMeter)
                    maskInfoCollected = true
                }
            }
        }

        naverMap.addOnCameraChangeListener { reason, animated ->
            if(reason == REASON_GESTURE)
            {
                gpsFollow = false
            }
        }

        naverMap.setOnMapClickListener { point, coord ->
            selectedInfoWindow.close()
        }
    }

    companion object{
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
    }
}

