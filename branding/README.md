# Pause — brand kit 0.4.0

Marchio originale: verde profondo, lime e due barre morbide di pausa. Il nome pubblico è **Pause**; il package Android storico resta invariato per gli aggiornamenti.

- `pause-symbol.svg`: simbolo completo per avatar e anteprime.
- `pause-mark.svg`: segno monocromatico, riempimento `currentColor`.
- `pause-wordmark.svg`: lettering originale in tracciati, senza font esterni.
- `pause-banner.svg`: banner su fondo crema, leggibile anche nel README GitHub in tema scuro.
- `icons/`: 22 icone SVG, viewBox24×24, tratto2, terminali e giunti arrotondati, colore `currentColor`.
- `icons.json`: catalogo sorgente dei tracciati.

Colori del marchio: verde `#122F25`, lime `#D5ED9A`. Conservare proporzioni e contrasto; lasciare spazio libero intorno al simbolo. Per le icone usare il colore semantico del tema. Le varianti Android sono VectorDrawable generati dagli stessi tracciati.

Rigenerazione: `python scripts/generate-brand.py`. Il kit non contiene chiavi di firma, credenziali o dati degli utenti.
