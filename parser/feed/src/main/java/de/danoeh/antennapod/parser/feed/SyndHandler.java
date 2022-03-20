package de.danoeh.antennapod.parser.feed;

import android.util.Log;

import de.danoeh.antennapod.parser.feed.util.TypeGetter;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.HashMap;
import java.util.function.Function;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.parser.feed.namespace.Content;
import de.danoeh.antennapod.parser.feed.namespace.DublinCore;
import de.danoeh.antennapod.parser.feed.namespace.Itunes;
import de.danoeh.antennapod.parser.feed.namespace.Media;
import de.danoeh.antennapod.parser.feed.namespace.Rss20;
import de.danoeh.antennapod.parser.feed.namespace.SimpleChapters;
import de.danoeh.antennapod.parser.feed.namespace.Namespace;
import de.danoeh.antennapod.parser.feed.namespace.PodcastIndex;
import de.danoeh.antennapod.parser.feed.element.SyndElement;
import de.danoeh.antennapod.parser.feed.namespace.Atom;

/** Superclass for all SAX Handlers which process Syndication formats */
public class SyndHandler extends DefaultHandler {
    private static final String TAG = "SyndHandler";
    private static final String DEFAULT_PREFIX = "";
    public final HandlerState state;

    private HashMap<String, HashMap<String, PrefixMappingInfo>> prefixMappings;

    private class PrefixMappingInfo {
        String logText;
        Class prefixClass;

        PrefixMappingInfo(String logText, Class prefixClass){
            this.logText = logText;
            this.prefixClass = prefixClass;
        }
    }

    public SyndHandler(Feed feed, TypeGetter.Type type) {
        state = new HandlerState(feed);
        if (type == TypeGetter.Type.RSS20 || type == TypeGetter.Type.RSS091) {
            state.defaultNamespaces.push(new Rss20());
        }

        prefixMappings = new HashMap<>();
        prefixMappings.put(Atom.NSURI, new HashMap<>());
        prefixMappings.get(Atom.NSURI).put(Atom.NSTAG, new PrefixMappingInfo("Recognized Atom namespace", Atom.class));
        prefixMappings.put(Content.NSURI, new HashMap<>());
        prefixMappings.get(Content.NSURI).put(Content.NSTAG, new PrefixMappingInfo("Recognized Content namespace", Content.class));
        prefixMappings.put(Itunes.NSURI, new HashMap<>());
        prefixMappings.get(Itunes.NSURI).put(Itunes.NSTAG, new PrefixMappingInfo("Recognized ITunes namespace", Itunes.class));
        prefixMappings.put(SimpleChapters.NSURI, new HashMap<>());
        prefixMappings.get(SimpleChapters.NSURI).put(SimpleChapters.NSTAG, new PrefixMappingInfo("Recognized SimpleChapters namespace", SimpleChapters.class));
        prefixMappings.put(Media.NSURI, new HashMap<>());
        prefixMappings.get(Media.NSURI).put(Media.NSTAG, new PrefixMappingInfo("Recognized Media namespace", Media.class));
        prefixMappings.put(DublinCore.NSURI, new HashMap<>());
        prefixMappings.get(DublinCore.NSURI).put(DublinCore.NSTAG, new PrefixMappingInfo("Recognized DublinCore namespace", DublinCore.class));
        prefixMappings.put(PodcastIndex.NSURI, new HashMap<>());
        prefixMappings.get(PodcastIndex.NSURI).put(PodcastIndex.NSTAG, new PrefixMappingInfo("Recognized PodcastIndex namespace", PodcastIndex.class));
    }

    @Override
    public void startElement(String uri, String localName, String qualifiedName,
            Attributes attributes) throws SAXException {
        state.contentBuf = new StringBuilder();
        Namespace handler = getHandlingNamespace(uri, qualifiedName);
        if (handler != null) {
            SyndElement element = handler.handleElementStart(localName, state,
                    attributes);
            state.tagstack.push(element);

        }
    }

    @Override
    public void characters(char[] ch, int start, int length)
            throws SAXException {
        if (!state.tagstack.empty()) {
            if (state.getTagstack().size() >= 2) {
                if (state.contentBuf != null) {
                    state.contentBuf.append(ch, start, length);
                }
            }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qualifiedName)
            throws SAXException {
        Namespace handler = getHandlingNamespace(uri, qualifiedName);
        if (handler != null) {
            handler.handleElementEnd(localName, state);
            state.tagstack.pop();

        }
        state.contentBuf = null;

    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        if (state.defaultNamespaces.size() > 1 && prefix.equals(DEFAULT_PREFIX)) {
            state.defaultNamespaces.pop();
        }
    }

    @Override
    public void startPrefixMapping(String prefix, String uri)
            throws SAXException {
        // Find the right namespace
        if (!state.namespaces.containsKey(uri)) {
            HashMap<String, PrefixMappingInfo> uriInfo = prefixMappings.get(uri);
            if(uriInfo != null){
                if (isDefaultNamespace(uri, prefix)) {
                    state.defaultNamespaces.push(new Atom());
                } else {
                    PrefixMappingInfo prefixInfo = uriInfo.get(prefix);
                    if(prefixInfo != null){
                        try {
                            state.namespaces.put(uri, (Namespace)prefixInfo.prefixClass.newInstance());
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        } catch (InstantiationException e) {
                            e.printStackTrace();
                        }
                        Log.d(TAG, prefixInfo.logText);
                    }
                }
            }
        }
    }

    private boolean isDefaultNamespace(String uri, String prefix){
        return prefix.equals(DEFAULT_PREFIX) && uri.equals(Atom.NSURI);
    }

    private Namespace getHandlingNamespace(String uri, String qualifiedName) {
        Namespace handler = state.namespaces.get(uri);
        if (handler == null && !state.defaultNamespaces.empty()
                && !qualifiedName.contains(":")) {
            handler = state.defaultNamespaces.peek();
        }
        return handler;
    }

    @Override
    public void endDocument() throws SAXException {
        super.endDocument();
        state.getFeed().setItems(state.getItems());
    }

    public HandlerState getState() {
        return state;
    }

}
