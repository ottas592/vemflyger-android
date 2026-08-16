package se.vemflyger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final long REFRESH_MS = 20_000L;

    private static final String PREFS = "vemflyger_settings";

    private static final double DEFAULT_LAT = 59.633810;
    private static final double DEFAULT_LON = 17.915602;
    private static final double DEFAULT_RADIUS_KM = 5.0;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private FlightWallView wallView;
    private SharedPreferences prefs;

    private volatile boolean requestRunning = false;

    private String lastRouteCallsign = "";
    private RouteInfo lastRoute = null;

    private final Runnable refreshRunnable =
            new Runnable() {
                @Override
                public void run() {
                    refreshAircraft();
                    handler.postDelayed(
                            this,
                            REFRESH_MS
                    );
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        prefs =
                getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                );

        configureWindow();

        wallView =
                new FlightWallView(this);

        /*
         * Extra skydd mot vanlig Android-skärmtimeout.
         */
        wallView.setKeepScreenOn(true);

        wallView.setOnLongPressListener(
                this::showSettings
        );

        wallView.setOnTapListener(
                this::refreshAircraft
        );

        setContentView(wallView);
    }

    @Override
    protected void onResume() {

        super.onResume();

        /*
         * Återaktivera keep-screen-on varje gång
         * appen kommer tillbaka till förgrunden.
         */
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        if (wallView != null) {
            wallView.setKeepScreenOn(true);
        }

        handler.removeCallbacks(
                refreshRunnable
        );

        handler.post(
                refreshRunnable
        );
    }

    @Override
    protected void onPause() {

        handler.removeCallbacks(
                refreshRunnable
        );

        super.onPause();
    }

    @Override
    protected void onDestroy() {

        executor.shutdownNow();

        super.onDestroy();
    }

    private void configureWindow() {

        Window window =
                getWindow();

        window.setStatusBarColor(
                Color.rgb(
                        7,
                        17,
                        31
                )
        );

        window.setNavigationBarColor(
                Color.rgb(
                        7,
                        17,
                        31
                )
        );

        /*
         * Primärt skydd mot skärmtimeout.
         */
        window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        window.getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
    }

    private double latitude() {

        return Double.longBitsToDouble(
                prefs.getLong(
                        "lat",
                        Double.doubleToLongBits(
                                DEFAULT_LAT
                        )
                )
        );
    }

    private double longitude() {

        return Double.longBitsToDouble(
                prefs.getLong(
                        "lon",
                        Double.doubleToLongBits(
                                DEFAULT_LON
                        )
                )
        );
    }

    private double radiusKm() {

        return Double.longBitsToDouble(
                prefs.getLong(
                        "radius",
                        Double.doubleToLongBits(
                                DEFAULT_RADIUS_KM
                        )
                )
        );
    }

    private void refreshAircraft() {

        if (requestRunning) {
            return;
        }

        requestRunning = true;

        final double lat =
                latitude();

        final double lon =
                longitude();

        final double radius =
                radiusKm();

        executor.execute(() -> {

            try {

                int nm =
                        Math.max(
                                3,
                                (int) Math.ceil(
                                        radius / 1.852
                                )
                        );

                String url =
                        String.format(
                                Locale.US,
                                "https://opendata.adsb.fi/api/v3/lat/%.6f/lon/%.6f/dist/%d",
                                lat,
                                lon,
                                nm
                        );

                JSONObject root =
                        getJson(url);

                JSONArray aircraft =
                        root.optJSONArray("ac");

                if (aircraft == null) {

                    aircraft =
                            root.optJSONArray(
                                    "aircraft"
                            );
                }

                AircraftInfo nearest =
                        null;

                if (aircraft != null) {

                    for (
                            int i = 0;
                            i < aircraft.length();
                            i++
                    ) {

                        JSONObject a =
                                aircraft.optJSONObject(i);

                        if (
                                a == null
                                        || !a.has("lat")
                                        || !a.has("lon")
                        ) {
                            continue;
                        }

                        double aLat =
                                a.optDouble(
                                        "lat",
                                        Double.NaN
                                );

                        double aLon =
                                a.optDouble(
                                        "lon",
                                        Double.NaN
                                );

                        if (
                                !Double.isFinite(aLat)
                                        || !Double.isFinite(aLon)
                        ) {
                            continue;
                        }

                        double distance =
                                haversineKm(
                                        lat,
                                        lon,
                                        aLat,
                                        aLon
                                );

                        if (distance > radius) {
                            continue;
                        }

                        if (
                                nearest == null
                                        || distance
                                        < nearest.distanceKm
                        ) {

                            nearest =
                                    AircraftInfo.fromJson(
                                            a,
                                            distance
                                    );
                        }
                    }
                }

                if (nearest == null) {

                    lastRouteCallsign = "";
                    lastRoute = null;

                    runOnUiThread(
                            () ->
                                    wallView.setFlight(
                                            null,
                                            null
                                    )
                    );

                } else {

                    RouteInfo route =
                            null;

                    String cs =
                            nearest.callsign;

                    if (
                            cs != null
                                    && !cs.isEmpty()
                    ) {

                        if (
                                cs.equals(
                                        lastRouteCallsign
                                )
                        ) {

                            route =
                                    lastRoute;

                        } else {

                            route =
                                    lookupRoute(cs);

                            lastRouteCallsign =
                                    cs;

                            lastRoute =
                                    route;
                        }
                    }

                    final AircraftInfo shown =
                            nearest;

                    final RouteInfo shownRoute =
                            route;

                    runOnUiThread(
                            () ->
                                    wallView.setFlight(
                                            shown,
                                            shownRoute
                                    )
                    );
                }

            } catch (Exception e) {

                e.printStackTrace();

            } finally {

                requestRunning =
                        false;
            }
        });
    }

    private RouteInfo lookupRoute(
            String callsign
    ) {

        try {

            String clean =
                    callsign
                            .trim()
                            .replace(
                                    " ",
                                    ""
                            )
                            .toUpperCase(
                                    Locale.US
                            );

            if (clean.isEmpty()) {
                return null;
            }

            JSONObject root =
                    getJson(
                            "https://api.adsbdb.com/v0/callsign/"
                                    + clean
                    );

            JSONObject response =
                    root.optJSONObject(
                            "response"
                    );

            if (response == null) {
                return null;
            }

            JSONObject f =
                    response.optJSONObject(
                            "flightroute"
                    );

            if (f == null) {
                return null;
            }

            return RouteInfo.fromJson(f);

        } catch (Exception e) {

            return null;
        }
    }

    private JSONObject getJson(
            String address
    ) throws Exception {

        HttpURLConnection conn =
                (HttpURLConnection)
                        new URL(address)
                                .openConnection();

        conn.setRequestMethod(
                "GET"
        );

        conn.setConnectTimeout(
                8_000
        );

        conn.setReadTimeout(
                8_000
        );

        conn.setRequestProperty(
                "Accept",
                "application/json"
        );

        conn.setRequestProperty(
                "User-Agent",
                "VemFlyger-Android/0.1"
        );

        int code =
                conn.getResponseCode();

        InputStream stream =
                code >= 200
                        && code < 300
                        ? conn.getInputStream()
                        : conn.getErrorStream();

        StringBuilder sb =
                new StringBuilder();

        if (stream != null) {

            try (
                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            stream,
                                            StandardCharsets.UTF_8
                                    )
                            )
            ) {

                String line;

                while (
                        (line = reader.readLine())
                                != null
                ) {

                    sb.append(line);
                }
            }
        }

        conn.disconnect();

        if (
                code < 200
                        || code >= 300
        ) {

            throw new Exception(
                    "HTTP "
                            + code
                            + ": "
                            + sb
            );
        }

        return new JSONObject(
                sb.toString()
        );
    }

    private void showSettings() {

        final LinearLayout layout =
                new LinearLayout(this);

        layout.setOrientation(
                LinearLayout.VERTICAL
        );

        int pad =
                dp(22);

        layout.setPadding(
                pad,
                dp(8),
                pad,
                0
        );

        EditText lat =
                field(
                        "Latitud",
                        latitude()
                );

        EditText lon =
                field(
                        "Longitud",
                        longitude()
                );

        EditText radius =
                field(
                        "Radie (km)",
                        radiusKm()
                );

        layout.addView(lat);
        layout.addView(lon);
        layout.addView(radius);

        AlertDialog dialog =
                new AlertDialog
                        .Builder(this)

                        .setTitle(
                                "Sökposition"
                        )

                        .setView(layout)

                        .setMessage(
                                "Håll fingret på skärmen för att öppna denna dialog igen. Ett kort tryck uppdaterar direkt."
                        )

                        .setNegativeButton(
                                "Avbryt",
                                null
                        )

                        .setPositiveButton(
                                "Spara",
                                null
                        )

                        .create();

        dialog.setOnShowListener(
                d ->
                        dialog
                                .getButton(
                                        AlertDialog.BUTTON_POSITIVE
                                )
                                .setOnClickListener(
                                        v -> {

                                            try {

                                                double newLat =
                                                        Double.parseDouble(
                                                                lat
                                                                        .getText()
                                                                        .toString()
                                                                        .replace(
                                                                                ',',
                                                                                '.'
                                                                        )
                                                        );

                                                double newLon =
                                                        Double.parseDouble(
                                                                lon
                                                                        .getText()
                                                                        .toString()
                                                                        .replace(
                                                                                ',',
                                                                                '.'
                                                                        )
                                                        );

                                                double newRadius =
                                                        Double.parseDouble(
                                                                radius
                                                                        .getText()
                                                                        .toString()
                                                                        .replace(
                                                                                ',',
                                                                                '.'
                                                                        )
                                                        );

                                                if (
                                                        newLat < -90
                                                                || newLat > 90
                                                                || newLon < -180
                                                                || newLon > 180
                                                                || newRadius <= 0
                                                                || newRadius > 50
                                                ) {

                                                    throw new IllegalArgumentException();
                                                }

                                                prefs.edit()

                                                        .putLong(
                                                                "lat",
                                                                Double.doubleToLongBits(
                                                                        newLat
                                                                )
                                                        )

                                                        .putLong(
                                                                "lon",
                                                                Double.doubleToLongBits(
                                                                        newLon
                                                                )
                                                        )

                                                        .putLong(
                                                                "radius",
                                                                Double.doubleToLongBits(
                                                                        newRadius
                                                                )
                                                        )

                                                        .apply();

                                                lastRouteCallsign =
                                                        "";

                                                lastRoute =
                                                        null;

                                                wallView.setFlight(
                                                        null,
                                                        null
                                                );

                                                dialog.dismiss();

                                                refreshAircraft();

                                            } catch (Exception ex) {

                                                Toast
                                                        .makeText(
                                                                this,
                                                                "Kontrollera koordinater och radie.",
                                                                Toast.LENGTH_SHORT
                                                        )
                                                        .show();
                                            }
                                        }
                                )
        );

        dialog.show();
    }

    private EditText field(
            String hint,
            double value
    ) {

        EditText edit =
                new EditText(this);

        edit.setHint(
                hint
        );

        edit.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        );

        edit.setText(
                String.format(
                        Locale.US,
                        "%.6f",
                        value
                )
        );

        return edit;
    }

    private int dp(int value) {

        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    static double haversineKm(
            double lat1,
            double lon1,
            double lat2,
            double lon2
    ) {

        double r =
                6371.0088;

        double dLat =
                Math.toRadians(
                        lat2 - lat1
                );

        double dLon =
                Math.toRadians(
                        lon2 - lon1
                );

        double q =
                Math.sin(dLat / 2)
                        * Math.sin(dLat / 2)
                        +
                        Math.cos(
                                Math.toRadians(lat1)
                        )
                                *
                                Math.cos(
                                        Math.toRadians(lat2)
                                )
                                *
                                Math.sin(dLon / 2)
                                * Math.sin(dLon / 2);

        return 2
                * r
                * Math.asin(
                        Math.sqrt(q)
                );
    }

    static String str(
            JSONObject o,
            String key
    ) {

        String s =
                o.optString(
                        key,
                        ""
                );

        return s == null
                ? ""
                : s.trim();
    }

    static final class AircraftInfo {

        String callsign;
        String registration;
        String type;

        double distanceKm;

        Double altitudeM;
        Double speedKmh;
        Double track;

        static AircraftInfo fromJson(
                JSONObject a,
                double distance
        ) {

            AircraftInfo x =
                    new AircraftInfo();

            x.callsign =
                    str(
                            a,
                            "flight"
                    );

            if (x.callsign.isEmpty()) {

                x.callsign =
                        str(
                                a,
                                "hex"
                        )
                                .toUpperCase(
                                        Locale.US
                                );
            }

            x.registration =
                    str(
                            a,
                            "r"
                    );

            x.type =
                    str(
                            a,
                            "t"
                    );

            x.distanceKm =
                    distance;

            Object alt =
                    a.opt(
                            "alt_baro"
                    );

            if (alt instanceof Number) {

                x.altitudeM =
                        ((Number) alt)
                                .doubleValue()
                                * 0.3048;

            } else if (
                    a.opt("alt_geom")
                            instanceof Number
            ) {

                x.altitudeM =
                        a.optDouble(
                                "alt_geom"
                        )
                                * 0.3048;
            }

            if (
                    a.opt("gs")
                            instanceof Number
            ) {

                x.speedKmh =
                        a.optDouble(
                                "gs"
                        )
                                * 1.852;
            }

            if (
                    a.opt("track")
                            instanceof Number
            ) {

                x.track =
                        a.optDouble(
                                "track"
                        );
            }

            return x;
        }
    }

    static final class RouteInfo {

        String publicFlight;
        String airline;

        String originCode;
        String originCity;

        String destinationCode;
        String destinationCity;

        static RouteInfo fromJson(
                JSONObject f
        ) {

            RouteInfo r =
                    new RouteInfo();

            r.publicFlight =
                    str(
                            f,
                            "callsign_iata"
                    );

            JSONObject airline =
                    f.optJSONObject(
                            "airline"
                    );

            if (airline != null) {

                r.airline =
                        str(
                                airline,
                                "name"
                        );
            }

            JSONObject origin =
                    f.optJSONObject(
                            "origin"
                    );

            if (origin != null) {

                r.originCode =
                        first(
                                str(
                                        origin,
                                        "iata_code"
                                ),
                                str(
                                        origin,
                                        "icao_code"
                                )
                        );

                r.originCity =
                        first(
                                str(
                                        origin,
                                        "municipality"
                                ),
                                str(
                                        origin,
                                        "name"
                                )
                        );
            }

            JSONObject destination =
                    f.optJSONObject(
                            "destination"
                    );

            if (destination != null) {

                r.destinationCode =
                        first(
                                str(
                                        destination,
                                        "iata_code"
                                ),
                                str(
                                        destination,
                                        "icao_code"
                                )
                        );

                r.destinationCity =
                        first(
                                str(
                                        destination,
                                        "municipality"
                                ),
                                str(
                                        destination,
                                        "name"
                                )
                        );
            }

            return r;
        }

        static String first(
                String a,
                String b
        ) {

            return a != null
                    && !a.isEmpty()
                    ? a
                    : b;
        }
    }

    static final class FlightWallView
            extends View {

        private final Paint p =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final Paint stroke =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final GestureDetector gestures;

        private AircraftInfo aircraft;
        private RouteInfo route;

        private Runnable onLongPress;
        private Runnable onTap;

        private final int bg =
                Color.rgb(
                        7,
                        17,
                        31
                );

        private final int panel =
                Color.rgb(
                        15,
                        31,
                        51
                );

        private final int line =
                Color.rgb(
                        48,
                        66,
                        87
                );

        private final int text =
                Color.rgb(
                        245,
                        248,
                        252
                );

        private final int muted =
                Color.rgb(
                        159,
                        177,
                        200
                );

        private final int accent =
                Color.rgb(
                        125,
                        211,
                        252
                );

        private final int accent2 =
                Color.rgb(
                        196,
                        181,
                        253
                );

        FlightWallView(
                Context context
        ) {

            super(context);

            setBackgroundColor(bg);

            /*
             * Även själva vyn begär att skärmen
             * hålls vaken.
             */
            setKeepScreenOn(true);

            stroke.setStyle(
                    Paint.Style.STROKE
            );

            stroke.setStrokeWidth(
                    dp(1)
            );

            gestures =
                    new GestureDetector(
                            context,
                            new GestureDetector
                                    .SimpleOnGestureListener() {

                                @Override
                                public boolean onDown(
                                        MotionEvent e
                                ) {
                                    return true;
                                }

                                @Override
                                public boolean onSingleTapConfirmed(
                                        MotionEvent e
                                ) {

                                    if (onTap != null) {
                                        onTap.run();
                                    }

                                    return true;
                                }

                                @Override
                                public void onLongPress(
                                        MotionEvent e
                                ) {

                                    if (onLongPress != null) {
                                        onLongPress.run();
                                    }
                                }
                            }
                    );
        }

        void setOnLongPressListener(
                Runnable r
        ) {

            onLongPress =
                    r;
        }

        void setOnTapListener(
                Runnable r
        ) {

            onTap =
                    r;
        }

        void setFlight(
                AircraftInfo a,
                RouteInfo r
        ) {

            aircraft =
                    a;

            route =
                    r;

            invalidate();
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            return gestures
                    .onTouchEvent(
                            event
                    );
        }

        @Override
        protected void onDraw(
                Canvas c
        ) {

            super.onDraw(c);

            /*
             * Om inget flyg finns inom radien
             * är skärmen helt tom.
             */
            if (aircraft == null) {
                return;
            }

            float w =
                    getWidth();

            float h =
                    getHeight();

            float shortSide =
                    Math.min(
                            w,
                            h
                    );

            float margin =
                    clamp(
                            shortSide * 0.045f,
                            dp(12),
                            dp(34)
                    );

            RectF card =
                    new RectF(
                            margin,
                            margin,
                            w - margin,
                            h - margin
                    );

            p.setColor(panel);
            p.setStyle(
                    Paint.Style.FILL
            );

            c.drawRoundRect(
                    card,
                    dp(26),
                    dp(26),
                    p
            );

            stroke.setColor(line);

            c.drawRoundRect(
                    card,
                    dp(26),
                    dp(26),
                    stroke
            );

            float inner =
                    clamp(
                            shortSide * 0.05f,
                            dp(14),
                            dp(38)
                    );

            float left =
                    card.left + inner;

            float right =
                    card.right - inner;

            float top =
                    card.top + inner;

            boolean portrait =
                    h > w;

            float callsignSize =
                    portrait
                            ? clamp(
                                    w * 0.13f,
                                    dp(34),
                                    dp(76)
                            )
                            : clamp(
                                    h * 0.18f,
                                    dp(42),
                                    dp(104)
                            );

            p.setTypeface(
                    android.graphics
                            .Typeface
                            .DEFAULT_BOLD
            );

            p.setTextSize(
                    callsignSize
            );

            p.setColor(text);

            p.setStyle(
                    Paint.Style.FILL
            );

            String displayFlight =
                    route != null
                            && notEmpty(
                                    route.publicFlight
                            )
                            ? route.publicFlight
                            : aircraft.callsign;

            c.drawText(
                    displayFlight == null
                            ? ""
                            : displayFlight,
                    left,
                    top
                            + callsignSize
                            * 0.82f,
                    p
            );

            float airlineSize =
                    callsignSize
                            * 0.26f;

            if (
                    route != null
                            && notEmpty(
                                    route.airline
                            )
            ) {

                p.setTypeface(
                        android.graphics
                                .Typeface
                                .DEFAULT
                );

                p.setTextSize(
                        airlineSize
                );

                p.setColor(accent);

                c.drawText(
                        route.airline,
                        left,
                        top
                                + callsignSize
                                + airlineSize
                                * 0.9f,
                        p
                );
            }

            float planeSize =
                    clamp(
                            shortSide * 0.19f,
                            dp(72),
                            dp(150)
                    );

            float planeCx =
                    right
                            - planeSize
                            * 0.55f;

            float planeCy =
                    top
                            + planeSize
                            * 0.58f;

            drawPlane(
                    c,
                    planeCx,
                    planeCy,
                    planeSize,
                    aircraft.track == null
                            ? 0
                            : aircraft
                            .track
                            .floatValue()
            );

            float y =
                    top
                            + callsignSize
                            + airlineSize
                            + dp(22);

            boolean hasRoute =
                    route != null
                            && notEmpty(
                                    route.originCode
                            )
                            && notEmpty(
                                    route.destinationCode
                            );

            if (hasRoute) {

                y =
                        drawRoute(
                                c,
                                left,
                                right,
                                y,
                                portrait
                        );
            }

            /*
             * Ingen footer eller datatext längre.
             * Hela återstående nederdelen kan användas
             * av informationsrutorna.
             */
            float metricsTop =
                    y + dp(8);

            float availableForMetrics =
                    card.bottom
                            - inner
                            - metricsTop;

            drawMetrics(
                    c,
                    left,
                    right,
                    metricsTop,
                    availableForMetrics,
                    portrait
            );
        }

        private float drawRoute(
                Canvas c,
                float left,
                float right,
                float y,
                boolean portrait
        ) {

            float height =
                    portrait
                            ? dp(100)
                            : dp(118);

            stroke.setColor(line);

            c.drawLine(
                    left,
                    y,
                    right,
                    y,
                    stroke
            );

            c.drawLine(
                    left,
                    y + height,
                    right,
                    y + height,
                    stroke
            );

            float cx1 =
                    left
                            + (right - left)
                            * 0.25f;

            float cx2 =
                    left
                            + (right - left)
                            * 0.75f;

            float codeSize =
                    portrait
                            ? clamp(
                                    getWidth()
                                            * 0.085f,
                                    dp(28),
                                    dp(52)
                            )
                            : clamp(
                                    getHeight()
                                            * 0.12f,
                                    dp(30),
                                    dp(66)
                            );

            p.setTypeface(
                    android.graphics
                            .Typeface
                            .DEFAULT_BOLD
            );

            p.setTextSize(
                    codeSize
            );

            p.setColor(text);

            drawCentered(
                    c,
                    route.originCode,
                    cx1,
                    y
                            + height
                            * 0.47f,
                    p
            );

            drawCentered(
                    c,
                    route.destinationCode,
                    cx2,
                    y
                            + height
                            * 0.47f,
                    p
            );

            p.setTypeface(
                    android.graphics
                            .Typeface
                            .DEFAULT
            );

            p.setTextSize(
                    clamp(
                            codeSize
                                    * 0.24f,
                            dp(10),
                            dp(15)
                    )
            );

            p.setColor(muted);

            drawCentered(
                    c,
                    ellipsize(
                            route.originCity,
                            22
                    ),
                    cx1,
                    y
                            + height
                            * 0.72f,
                    p
            );

            drawCentered(
                    c,
                    ellipsize(
                            route.destinationCity,
                            22
                    ),
                    cx2,
                    y
                            + height
                            * 0.72f,
                    p
            );

            p.setTextSize(
                    codeSize
                            * 0.62f
            );

            p.setColor(accent2);

            drawCentered(
                    c,
                    "▶",
                    (left + right) / 2f,
                    y
                            + height
                            * 0.51f,
                    p
            );

            return y
                    + height
                    + dp(12);
        }

        private void drawMetrics(
                Canvas c,
                float left,
                float right,
                float y,
                float availableHeight,
                boolean portrait
        ) {

            String[] labels = {
                    "AVSTÅND",
                    "HÖJD",
                    "HASTIGHET",
                    "KURS"
            };

            String[] values = {

                    String.format(
                            new Locale(
                                    "sv",
                                    "SE"
                            ),
                            "%.1f KM",
                            aircraft.distanceKm
                    ).replace(
                            '.',
                            ','
                    ),

                    aircraft.altitudeM == null
                            ? "-"
                            : String.format(
                                    Locale.US,
                                    "%.0f M",
                                    aircraft.altitudeM
                            ),

                    aircraft.speedKmh == null
                            ? "-"
                            : String.format(
                                    Locale.US,
                                    "%.0f KM/H",
                                    aircraft.speedKmh
                            ),

                    aircraft.track == null
                            ? "-"
                            : direction(
                                    aircraft.track
                            )
                            + " "
                            + Math.round(
                                    aircraft.track
                            )
                            + "°"
            };

            int cols =
                    portrait
                            ? 2
                            : 4;

            int rows =
                    4 / cols;

            float gap =
                    dp(8);

            float boxW =
                    (
                            right
                                    - left
                                    - gap
                                    * (cols - 1)
                    )
                            / cols;

            float preferredBoxHeight =
                    portrait
                            ? dp(78)
                            : dp(58);

            float maximumBoxHeight =
                    (
                            availableHeight
                                    - gap
                                    * (rows - 1)
                    )
                            / rows;

            float boxH =
                    Math.min(
                            preferredBoxHeight,
                            maximumBoxHeight
                    );

            boxH =
                    Math.max(
                            boxH,
                            dp(50)
                    );

            for (
                    int i = 0;
                    i < 4;
                    i++
            ) {

                int row =
                        i / cols;

                int col =
                        i % cols;

                float x =
                        left
                                + col
                                * (boxW + gap);

                float yy =
                        y
                                + row
                                * (boxH + gap);

                RectF r =
                        new RectF(
                                x,
                                yy,
                                x + boxW,
                                yy + boxH
                        );

                p.setColor(
                        Color.rgb(
                                17,
                                35,
                                57
                        )
                );

                c.drawRoundRect(
                        r,
                        dp(16),
                        dp(16),
                        p
                );

                stroke.setColor(line);

                c.drawRoundRect(
                        r,
                        dp(16),
                        dp(16),
                        stroke
                );

                /*
                 * Rubrik.
                 */
                p.setTypeface(
                        android.graphics
                                .Typeface
                                .DEFAULT
                );

                p.setTextSize(
                        portrait
                                ? dp(10)
                                : dp(9)
                );

                p.setColor(muted);

                float textX =
                        x + dp(10);

                float labelY =
                        yy + dp(18);

                c.drawText(
                        labels[i],
                        textX,
                        labelY,
                        p
                );

                /*
                 * Värde.
                 */
                p.setTypeface(
                        android.graphics
                                .Typeface
                                .DEFAULT_BOLD
                );

                p.setTextSize(
                        portrait
                                ? dp(24)
                                : dp(21)
                );

                p.setColor(text);

                float valueY =
                        yy
                                + boxH
                                - dp(10);

                c.drawText(
                        values[i],
                        textX,
                        valueY,
                        p
                );
            }
        }

        private void drawPlane(
                Canvas c,
                float cx,
                float cy,
                float size,
                float heading
        ) {

            c.save();

            c.rotate(
                    heading,
                    cx,
                    cy
            );

            float s =
                    size / 32f;

            Path path =
                    new Path();

            path.moveTo(
                    cx + (15 - 16) * s,
                    cy + (1 - 16) * s
            );

            path.lineTo(
                    cx + (17 - 16) * s,
                    cy + (1 - 16) * s
            );

            path.lineTo(
                    cx + (19 - 16) * s,
                    cy + (12 - 16) * s
            );

            path.lineTo(
                    cx + (29 - 16) * s,
                    cy + (16 - 16) * s
            );

            path.lineTo(
                    cx + (29 - 16) * s,
                    cy + (19 - 16) * s
            );

            path.lineTo(
                    cx + (19 - 16) * s,
                    cy + (17 - 16) * s
            );

            path.lineTo(
                    cx + (18 - 16) * s,
                    cy + (25 - 16) * s
            );

            path.lineTo(
                    cx + (22 - 16) * s,
                    cy + (28 - 16) * s
            );

            path.lineTo(
                    cx + (22 - 16) * s,
                    cy + (30 - 16) * s
            );

            path.lineTo(
                    cx,
                    cy + (28 - 16) * s
            );

            path.lineTo(
                    cx + (10 - 16) * s,
                    cy + (30 - 16) * s
            );

            path.lineTo(
                    cx + (10 - 16) * s,
                    cy + (28 - 16) * s
            );

            path.lineTo(
                    cx + (14 - 16) * s,
                    cy + (25 - 16) * s
            );

            path.lineTo(
                    cx + (13 - 16) * s,
                    cy + (17 - 16) * s
            );

            path.lineTo(
                    cx + (3 - 16) * s,
                    cy + (19 - 16) * s
            );

            path.lineTo(
                    cx + (3 - 16) * s,
                    cy + (16 - 16) * s
            );

            path.lineTo(
                    cx + (13 - 16) * s,
                    cy + (12 - 16) * s
            );

            path.close();

            p.setColor(accent);

            p.setStyle(
                    Paint.Style.FILL
            );

            c.drawPath(
                    path,
                    p
            );

            c.restore();
        }

        private static void drawCentered(
                Canvas c,
                String s,
                float cx,
                float baseline,
                Paint p
        ) {

            if (s == null) {
                s = "";
            }

            c.drawText(
                    s,
                    cx
                            - p.measureText(s)
                            / 2f,
                    baseline,
                    p
            );
        }

        private static String direction(
                double d
        ) {

            String[] dirs = {
                    "N",
                    "NÖ",
                    "Ö",
                    "SÖ",
                    "S",
                    "SV",
                    "V",
                    "NV"
            };

            int i =
                    (int) Math.round(
                            (
                                    (
                                            (d % 360)
                                                    + 360
                                    )
                                            % 360
                            )
                                    / 45.0
                    )
                            % 8;

            return dirs[i];
        }

        private static String ellipsize(
                String s,
                int max
        ) {

            if (s == null) {
                return "";
            }

            return s.length() <= max
                    ? s
                    : s.substring(
                            0,
                            Math.max(
                                    0,
                                    max - 1
                            )
                    )
                    + "…";
        }

        private static boolean notEmpty(
                String s
        ) {

            return s != null
                    && !s.trim().isEmpty();
        }

        private static float clamp(
                float v,
                float min,
                float max
        ) {

            return Math.max(
                    min,
                    Math.min(
                            max,
                            v
                    )
            );
        }

        private int dp(
                int value
        ) {

            return Math.round(
                    value
                            * getResources()
                            .getDisplayMetrics()
                            .density
            );
        }
    }
}
