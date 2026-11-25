# Document reader dependencies

- PDFBox-Android 2.0.27.0, Apache License 2.0: https://github.com/TomRoush/PdfBox-Android
- jsoup 1.22.2, MIT License: https://jsoup.org/license

The Android distribution must retain applicable upstream notices and license files. The PDF reader uses local text extraction only; it does not render images or execute PDF actions. HTML parsing does not execute scripts or fetch resources. RTF support covers common text and Unicode control words, not all advanced RTF layout constructs.

The compact build excludes optional Bouncy Castle public-key encryption and signature libraries. Password-protected/encrypted PDFs are not a supported reading format. Android 15+ can extract new PDF books with the platform renderer; older devices and existing legacy bookmarks use PDFBox. HTML retains jsoup for robust decoding and consistent existing character bookmarks.
