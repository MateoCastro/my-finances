# Reglas de R8/ProGuard para el build release.

# PdfBox-Android (Hito 4) referencia el decodificador opcional de JPEG2000
# (com.gemalto.jp2:jp2-android), que NO incluimos como dependencia: los
# extractos son PDFs de texto, nunca usan imágenes JPEG2000. Sin esto, R8
# aborta el release por "Missing class". Es seguro ignorarlo: el código de
# JPXFilter solo se ejecuta ante un stream JPEG2000, que no se da aquí.
-dontwarn com.gemalto.jp2.JP2Decoder
