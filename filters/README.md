# Liste di protezione di Pause

Pause 0.4.0 combina **StevenBlack/hosts** e **HaGeZi Normal**. Questa cartella distribuisce i dati originali HaGeZi usati nello snapshot incluso, la relativa licenza e i metadati delle fonti. Non contiene il codice dell’app.

| File | Contenuto |
| --- | --- |
| [hagezi-normal.txt](hagezi-normal.txt) | File originale HaGeZi Normal, formato domini, incluso nella release 0.4.0. |
| [LICENSE-HAGEZI.txt](LICENSE-HAGEZI.txt) | Licenza GNU GPL versione 3 associata ai dati HaGeZi. |
| [sources.json](sources.json) | URL originali, data di acquisizione, SHA-256 e metadati dello snapshot. |

Fonti originali: [StevenBlack/hosts](https://github.com/StevenBlack/hosts) e [HaGeZi DNS Blocklists](https://github.com/hagezi/dns-blocklists). Il file HaGeZi proviene da [wildcard/multi-onlydomains.txt](https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/multi-onlydomains.txt). La lista StevenBlack conserva le attribuzioni delle fonti e la licenza MIT dell’aggregatore; sono disponibili nell’app insieme alla licenza HaGeZi.

## Come viene usato lo snapshot

Il parser di Pause legge 76.147 domini StevenBlack validi e 172.000 domini HaGeZi. La combinazione elimina regole già coperte da un dominio padre e produce **201.381 regole compatte**, alle quali l’app aggiunge le regole SDK incluse. Rispetto alla lista precedente, **163.267 domini HaGeZi** non erano già coperti, nemmeno dai relativi domini padre. Queste quantità descrivono le liste; non misurano annunci rimossi o risultati su YouTube e Instagram.

Gli aggiornamenti nell’app scaricano entrambe le fonti via HTTPS e le validano prima di sostituire atomicamente la lista locale. Se una fonte non è valida o non si scarica, resta la lista precedente. I file qui pubblicati documentano lo snapshot della release: non vengono sostituiti da ogni aggiornamento automatico effettuato sul telefono.

Il filtro DNS non può distinguere inserzioni e contenuti distribuiti dalla stessa infrastruttura. Pause non promette la rimozione delle inserzioni integrate delle app ufficiali YouTube e Instagram; la navigazione con la sola VPN ha lo stesso limite. Un filtro dei contenuti del browser è una funzione separata.
