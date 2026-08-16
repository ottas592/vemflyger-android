# Vem flyger? – Android

Första native Android-versionen av Flight Wall-idén.

## Funktioner

- Liveposition från **adsb.fi** var 20:e sekund.
- Testposition: **59.633810, 17.915602** (Arlanda).
- Standardradie: **5 km**.
- Om flera plan finns i området visas endast det närmaste.
- Ruttuppslagning från **ADSBDB** när callsign ändras.
- Visar publikt flightnummer när ADSBDB känner till det, annars ADS-B-callsign.
- Visar avgångsflygplats, destination och flygbolag när uppgifterna finns.
- Flygplanssymbolen roteras efter rapporterad kurs.
- Responsiv native layout för mobil och surfplatta.
- Om inget plan finns inom radien är visningen helt tom.
- Kort tryck på skärmen = uppdatera nu.
- Långtryck på skärmen = ändra latitud, longitud och radie.
- Inställningarna sparas lokalt på enheten.
- Skärmen hålls vaken medan appen är öppen.

## Bygg en APK via GitHub

Projektet innehåller `.github/workflows/build-apk.yml`.

1. Lägg projektets innehåll i ett GitHub-repository.
2. Öppna fliken **Actions** i GitHub.
3. Välj **Build Android APK**.
4. Klicka **Run workflow** (eller gör en commit till `main`).
5. När bygget är klart, öppna körningen och hämta artefakten **vemflyger-debug-apk**.
6. ZIP-filen från GitHub innehåller `app-debug.apk`. Överför APK:n till Android-enheten och installera den.

Android kan be dig tillåta installation från webbläsaren/filläsaren första gången.

## Bygg lokalt i Android Studio

Öppna projektmappen i Android Studio och välj **Build > Build APK(s)**.

## Datakällor

- Live ADS-B: https://adsb.fi – `opendata.adsb.fi/api/v3/lat/.../lon/.../dist/...`
- Rutt/flightnummer: https://adsbdb.com – `api.adsbdb.com/v0/callsign/...`

Appen gör liveanrop var 20:e sekund. ADSBDB anropas bara när det visade planets callsign ändras.

## Ändra slutposition

Långtryck var som helst i appen. Ändra koordinaterna och tryck **Spara**. Ingen ny APK behöver byggas för att byta position.
