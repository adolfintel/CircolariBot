/*
 * Copyright (C) 2021-2025 Federico Dossena
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.dosse.circolaribot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import org.ini4j.Wini;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 *
 * @author dosse
 */
public class Main {

    private static final String URL = "https://galileicrema.edu.it/novita/circolari?field_tax_anno_scolastico_target_id=All&field_numero_circolare_value=&field_oggetto_value=&field_tax_destinatari_target_id=All&page=$$$PAGE$$$"; //URL da tenere controllato ($$$PAGE$$$ verrà sostituito col numero di pagine)
    private static final String CONFIG_FILENAME = "config.ini"; //file da cui caricare la configurazione
    private static final String STATE_FILENAME = "state-v1.dat"; //file su cui salvare/caricare lo stato (per evitare di ripetere i post in caso di riavvio del bot)

    private static final long CHECK_NEW_INTERVAL = 600000; //controlla nuove circolari ogni 10 minuti
    private static final long CHECK_PDFUPDATE_EVERY_INTERVALS = 18; //controlla aggiornamenti delle circolari vecchie ogni 18 intervalli (3 ore)
    private static final int CHECK_PAGES = 5; //controlla solo le prime 5 pagine
    private static final long DELAY_BETWEEN_POSTS = 5000; //sleep di 5 secondi tra i post per evitare il rate limiting di telegram
    private static final long DELAY_BETWEEN_RECONNECTS = 60000; //se la connessione a telegram va giù, ritenta ogni 1 minuto
    private static final long DELAY_BETWEEN_PDFHASHES = 3000; //aspetta 3 secondi tra un download di un PDF e un altro
    private static final long DELAY_BETWEEN_LISTS = 3000; //aspetta 3 secondi tra una pagina e un'altra nell'elenco delle circolari
    private static final long DELAY_BETWEEN_LIST_AND_PAGE = 3000; //aspetta 3 secondi tra lo scaricamento dell'elenco e l'apertura di una circolare

    private static final String APP_NAME = "CircolariBot", APP_VERSION = "1.4.0";
    private static final String USER_AGENT = APP_NAME + "/" + APP_VERSION;

    private static String token = null; //ID del bot fornito da botfather
    private static String channel = null; //ID del canale su cui postare le notifiche (il bot deve essere admin)
    private static TelegramBot bot = null; //collegamento a telegram
    private static long delayAtStart = 0; //quanti ms aspettare all'avvio prima di connettersi a telegram (in caso sia avviato dal sistema prima di avere una connessione funzionante)

    private static boolean testMode = false; //se attivata scrive sul terminale anzichè inviare sul canale

    //HashMap con le info delle circolari che il bot ha visto nel corso della sua vita
    //Se sei un mio studente, questo è il motivo per cui vi dico di fare l'analisi e sviluppare soluzioni flessibili! Inizialmente c'era solo uno di questi tre, per cui andava bene, adesso invece dovrebbe essere una classe, ma facendolo cambierei il formato di salvataggio del file state-v1.dat per cui sono costretto a farlo così
    private static HashMap<String, Long> alreadyPosted; //map url->timestamp dei post (per distinguere elementi nuovi da quelli vecchi)
    private static HashMap<String, byte[]> pdfHashes;  //map url->hash dei PDF delle circolari (per rilevare aggiornamenti di circolari già viste)
    private static HashMap<String, Integer> numberOfUpdates; //map url->int del numero di volte che una circolare viene aggiornata

    private static int loopCounter = 0;

    public static void main(String[] args) {
        System.out.println("--- " + APP_NAME + " v" + APP_VERSION + " ---");
        if (args.length != 0 && args[0].equalsIgnoreCase("--test")) {
            testMode = true;
            System.out.println("Modalità test attiva, i messaggi verranno scritti sul terminale anzichè su telegram");
        }
        loadConfig();
        loadState();
        if (delayAtStart > 0) {
            System.out.println("Aspetto " + delayAtStart + "ms prima di iniziare");
            sleep(delayAtStart);
            System.out.println("Pronto");
        }
        for (;;) {
            long ts = System.currentTimeMillis();
            loopCounter++;
            botLoop();
            System.gc();
            sleep(CHECK_NEW_INTERVAL - (System.currentTimeMillis() - ts));
        }
    }

    private static void loadConfig() {
        try {
            Wini ini = new Wini(new File(CONFIG_FILENAME));
            token = ini.get("CircolariBot", "TOKEN");
            channel = ini.get("CircolariBot", "CHANNEL_ID");
            String s=ini.get("CircolariBot", "DELAY_AT_START");
            if(s!=null){
                delayAtStart = Long.parseLong(s);
            }
        } catch (Throwable t) {
            System.err.println("Errore: impossibile caricare la configurazione\n\n"
                    + "Il file " + CONFIG_FILENAME + " deve avere la seguente sintassi:\n"
                    + "[CircolariBot]\n"
                    + "TOKEN=id fornito da botfather\n"
                    + "CHANNEL_ID=id del canale di cui il bot è admin\n"
                    + "DELAY_AT_START=ms da attendere all'avvio (opzionale)\n\n");
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void botLoop() {
        ArrayList<SendMessage> postsToSend = new ArrayList<>();
        try {
            for (int currentPage = 1; currentPage <= CHECK_PAGES; currentPage++) {
                System.out.println(URL.replace("$$$PAGE$$$", "" + currentPage));
                Document doc = Jsoup.connect(URL.replace("$$$PAGE$$$", "" + currentPage)).userAgent(USER_AGENT).get();
                sleep(DELAY_BETWEEN_LISTS);
                Elements circolari = doc.select("div.card-body");
                if (circolari.isEmpty()) {
                    System.err.println("Nessuna circolare estratta, forse è cambiato qualcosa nel sito?");
                    continue;
                }
                boolean checkForPdfUpdates = loopCounter % CHECK_PDFUPDATE_EVERY_INTERVALS == 0; //quando è true controlla gli aggiornamenti delle circolari vecchie anzichè cercare circolari nuove
                int n = 0;
                for (Element e : circolari) {
                    try {
                        String numero = "?", titolo = "", descrizione = "", link = null, data = "?";
                        try {
                            numero = e.select("div.head-tags span.card-tag").first().text().split("-")[1].trim();
                            if (!numero.startsWith("n.")) {
                                numero = "n." + numero;
                            }
                        } catch (Throwable t) {
                        }
                        try {
                            titolo = e.select("h3.card-title").first().text();
                        } catch (Throwable t) {
                        }
                        try {
                            descrizione = e.select("div.field--name-field-breve-descrizione p").first().text();
                        } catch (Throwable t) {
                        }
                        try {
                            link = e.select("a.btn").first().attributes().get("href");
                            if (!link.startsWith("/")) {
                                link = "/" + link;
                            }
                            link = "https://galileicrema.edu.it" + link;
                        } catch (Throwable t) {
                        }
                        try {
                            data = e.select("div.head-tags span.data").first().text();
                        } catch (Throwable t) {
                        }
                        if (checkForPdfUpdates) {
                            sleep(DELAY_BETWEEN_LIST_AND_PAGE);
                            String[] pdfUrls = getPdfUrls(link);
                            byte[] oldHash = pdfHashes.get(link);
                            if (oldHash != null) {
                                byte[] newHash = getPdfHashes(pdfUrls);
                                if (newHash != null && newHash.length == oldHash.length) {
                                    boolean updated = false;
                                    for (int i = 0; i < newHash.length; i++) {
                                        if (newHash[i] != oldHash[i]) {
                                            updated = true;
                                            break;
                                        }
                                    }
                                    if (updated) {
                                        int nUpdates = numberOfUpdates.getOrDefault(link, 0) + 1;
                                        numberOfUpdates.put(link, nUpdates);
                                        pdfHashes.put(link, newHash);
                                        String post = "Aggiornamento circolare " + numero + " del " + data + " (agg." + nUpdates + ")" + "\n" + titolo + "\n" + descrizione + "\n";
                                        for (String pdfUrl : pdfUrls) {
                                            post += pdfUrl + "?ts=" + System.currentTimeMillis() + "\n";
                                        }
                                        post = post.trim();
                                        postsToSend.add(new SendMessage(channel, post));
                                    }
                                }
                            }
                        } else {
                            if (alreadyPosted.get(link) == null) {
                                sleep(DELAY_BETWEEN_LIST_AND_PAGE);
                                String[] pdfUrls = getPdfUrls(link);
                                byte[] hash = getPdfHashes(pdfUrls);
                                if (hash == null) {
                                    System.err.println("Impossibile ottenere il file della circolare");
                                } else {
                                    String post = "Circolare " + numero + " del " + data + "\n" + titolo + "\n" + descrizione + "\n";
                                    for (String pdfUrl : pdfUrls) {
                                        post += pdfUrl + "?ts=" + System.currentTimeMillis() + "\n";
                                    }
                                    postsToSend.add(new SendMessage(channel, post));
                                    alreadyPosted.put(link, System.nanoTime());
                                    pdfHashes.put(link, hash);
                                }
                            }
                        }
                        n++;
                    } catch (Throwable t) {
                        System.err.println("Fallita l'estrazione delle informazioni dalla circolare. Dati grezzi: " + e);
                        t.printStackTrace(System.err);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("Errore durante il recupero delle circolari");
            t.printStackTrace(System.err);
        }
        if (!postsToSend.isEmpty()) {
            if (bot == null) {
                connectToTelegram();
            }
            Collections.reverse(postsToSend);
            for (SendMessage m : postsToSend) {
                for (;;) {
                    try {
                        if (testMode) {
                            System.out.println("Post:\n" + m.getParameters().get("text") + "\n\n");
                        } else {
                            bot.execute(m);
                        }
                        break;
                    } catch (Throwable t) {
                        System.err.println("Errore durante l'invio di un post, tentativo di riconnessione");
                        connectToTelegram();
                    }
                }
                sleep(DELAY_BETWEEN_POSTS);
            }
            saveState();
        }
    }

    private static void connectToTelegram() {
        for (;;) {
            try {
                bot = new TelegramBot(token);
                break;
            } catch (Throwable t) {
                System.err.println("Errore durante la connessione a telegram, riprovo tra poco");
                t.printStackTrace(System.err);
                sleep(DELAY_BETWEEN_RECONNECTS);
            }
        }
    }

    private static void loadState() {
        ObjectInputStream ois = null;
        HashMap<String, Long> alreadyPosted;
        HashMap<String, byte[]> pdfHashes;
        HashMap<String, Integer> numberOfUpdates;
        try {
            ois = new ObjectInputStream(new FileInputStream(STATE_FILENAME));
            alreadyPosted = (HashMap<String, Long>) ois.readObject();
            try {
                pdfHashes = (HashMap<String, byte[]>) ois.readObject();
            } catch (Throwable t) {
                pdfHashes = new HashMap<String, byte[]>();
                System.err.println("Caricato stato salvato da una versione precedente del bot");
            }
            try {
                numberOfUpdates = (HashMap<String, Integer>) ois.readObject();
            } catch (Throwable t) {
                numberOfUpdates = new HashMap<String, Integer>();
                System.err.println("Caricato stato salvato da una versione precedente del bot");
            }
        } catch (Throwable t) {
            System.err.println("Stato salvato mancante o corrotto, il bot riparte da zero");
            t.printStackTrace(System.err);
            alreadyPosted = new HashMap<String, Long>();
            pdfHashes = new HashMap<String, byte[]>();
            numberOfUpdates = new HashMap<String, Integer>();
        }
        Main.alreadyPosted = alreadyPosted;
        Main.pdfHashes = pdfHashes;
        Main.numberOfUpdates = numberOfUpdates;
        try {
            ois.close();
        } catch (Throwable ex) {
        }
        System.out.println("Caricati " + alreadyPosted.size() + " link e " + pdfHashes.size() + " hash");
    }

    private static void saveState() {
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(new FileOutputStream(STATE_FILENAME));
            oos.writeObject(alreadyPosted);
            oos.writeObject(pdfHashes);
            oos.writeObject(numberOfUpdates);
            oos.flush();
        } catch (Throwable t) {
            System.err.println("Impossibile salvare lo stato");
        }
        try {
            oos.close();
        } catch (Throwable ex) {
        }
    }

    private static final void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
        }
    }

    private static final String[] getPdfUrls(String link) {
        ArrayList<String> urls = new ArrayList<>();
        try {
            Document doc = Jsoup.connect(link).userAgent(USER_AGENT).get();
            Elements elements = doc.select("h3.h5.card-title a");
            for (Element e : elements) {
                String url = e.attr("href");
                if (!url.startsWith("/")) {
                    url = "/" + url;
                }
                urls.add("https://galileicrema.edu.it" + url);
            }
            if (urls.isEmpty()) {
                return null;
            }
            return urls.toArray(new String[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private static final byte[] getPdfHashes(String[] urls) {
        ByteArrayOutputStream ret = new ByteArrayOutputStream();
        for (String url : urls) {
            byte[] data = null;
            InputStream in = null;
            try {
                URLConnection c = new URL(url).openConnection();
                c.setDefaultUseCaches(false);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.connect();
                in = c.getInputStream();
            } catch (Throwable ex) {
                try {
                    in.close();
                } catch (Throwable t) {
                }
                return null;
            }
            try {
                data = in.readAllBytes();
            } catch (Throwable ex) {
                try {
                    in.close();
                } catch (Throwable t) {
                }
                return null;
            }
            try {
                in.close();
            } catch (Throwable t) {
            }
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                ret.write(md.digest(data));
                ret.flush();
            } catch (Throwable t) {
                t.printStackTrace();
                return null;
            }
            sleep(DELAY_BETWEEN_PDFHASHES);
        }
        return ret.toByteArray();
    }

}
