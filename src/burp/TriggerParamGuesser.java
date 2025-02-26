package burp;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.graalvm.compiler.core.common.util.Util;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

import static java.lang.Math.min;
import static org.apache.commons.lang3.math.NumberUtils.max;


class TriggerParamGuesser implements ActionListener, Runnable {
    private IHttpRequestResponse[] reqs;
    private boolean backend;
    private byte type;
    private ParamGrabber paramGrabber;
    private ThreadPoolExecutor taskEngine;
    private ConfigurableSettings config;

    TriggerParamGuesser(IHttpRequestResponse[] reqs, boolean backend, byte type, ParamGrabber paramGrabber, ThreadPoolExecutor taskEngine) {
        this.taskEngine = taskEngine;
        this.paramGrabber = paramGrabber;
        this.backend = backend;
        this.type = type;
        this.reqs = reqs;
    }

    public void actionPerformed(ActionEvent e) {
        ConfigurableSettings config = Utilities.globalSettings.showSettings();
        if (config != null) {
            this.config = config;
            //Runnable runnable = new TriggerParamGuesser(reqs, backend, type, paramGrabber, taskEngine, config);
            (new Thread(this)).start();
        }
    }

    public void run() {
        int queueSize = taskEngine.getQueue().size();
        Utilities.log("Adding "+reqs.length+" tasks to queue of "+queueSize);
        queueSize += reqs.length;
        int thread_count = taskEngine.getCorePoolSize();

        int stop = config.getInt("rotation interval");
        if (queueSize < thread_count) {
            stop = 256;
        }

        ArrayList<IHttpRequestResponse> reqlist = new ArrayList<>(Arrays.asList(reqs));
        Collections.shuffle(reqlist);

        // If guessing smuggling mutations, downgrade HTTP/2 requests to HTTP/1.1
        if (config.getBoolean("identify smuggle mutations") && this.type == Utilities.PARAM_HEADER) {
            Iterator iterator = reqlist.iterator();
            for (int i = 0; i < reqlist.size(); i++) {
                IHttpRequestResponse req = reqlist.get(i);
                if (!Utilities.isHTTP2(req.getRequest())) {
                    continue;
                }
                byte[] downgraded = Utilities.convertToHttp1(req.getRequest());
                String host = req.getHttpService().getHost();
                int port = req.getHttpService().getPort();
                String proto = req.getHttpService().getProtocol();
                IHttpService service = Utilities.helpers.buildHttpService(host, port, proto);

                IHttpRequestResponse newReq = Utilities.attemptRequest(service, downgraded, true);
                reqlist.set(i, newReq);
                this.reqs[i] = newReq;
            }
        }

        int cache_size = thread_count;
        if (config.getBoolean("max one per host")) {
            cache_size = queueSize;
        }

        Set<String> keyCache = new HashSet<>();
        boolean useKeyCache = config.getBoolean("max one per host+status");

        Queue<String> cache = new CircularFifoQueue<>(cache_size);
        HashSet<String> remainingHosts = new HashSet<>();

        boolean canSkip = false;
        byte[] noCache = "no-cache".getBytes();
        if (config.getBoolean("skip uncacheable") && (type == IParameter.PARAM_COOKIE || type == Utilities.PARAM_HEADER)) {
            canSkip = true;
        }


        int i = 0;
        int queued = 0;
        // every pass adds at least one item from every host
        while(!reqlist.isEmpty()) {
            Utilities.log("Loop "+i++);
            Iterator<IHttpRequestResponse> left = reqlist.iterator();
            while (left.hasNext()) {
                IHttpRequestResponse req = left.next();

                String host = req.getHttpService().getHost();
                String key = req.getHttpService().getProtocol()+host;
                if (req.getResponse() != null) {
                    if (canSkip && Utilities.containsBytes(req.getResponse(), noCache)) {
                        continue;
                    }

                    IResponseInfo info = Utilities.helpers.analyzeResponse(req.getResponse());
                    key = key + info.getStatusCode() + info.getInferredMimeType();
                }

                if (useKeyCache && keyCache.contains(key)) {
                    left.remove();
                    continue;
                }

                if (!cache.contains(host)) {
                    cache.add(host);
                    keyCache.add(key);
                    left.remove();
                    Utilities.log("Adding request on "+host+" to queue");
                    queued++;
                    taskEngine.execute(new ParamGuesser(Utilities.callbacks.saveBuffersToTempFiles(req), backend, type, paramGrabber, taskEngine, stop, config));
                } else {
                    remainingHosts.add(host);
                }
            }

            if(config.getBoolean("max one per host")) {
                break;
            }

            if (remainingHosts.size() <= 1 && !useKeyCache) {
                left = reqlist.iterator();
                while (left.hasNext()) {
                    queued++;
                    taskEngine.execute(new ParamGuesser(Utilities.callbacks.saveBuffersToTempFiles(left.next()), backend, type, paramGrabber, taskEngine, stop, config));
                }
                break;
            }
            else {
                cache = new CircularFifoQueue<>(max(min(remainingHosts.size()-1, thread_count), 1));
            }
        }

        Utilities.out("Queued " + queued + " attacks");

    }
}