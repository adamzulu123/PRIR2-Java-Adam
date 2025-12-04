import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class DyspozytorniaWatkowa implements Dyspozytornia {
    private final AtomicInteger zlecenieId = new AtomicInteger(1);

    //zlecenia
    Comparator<Zlecenie> zlecenieComparator = Comparator
            .comparing(Zlecenie::priority).reversed()
            .thenComparing(Zlecenie::createdAt);
    private final PriorityBlockingQueue<Zlecenie> zlecenieQueue = new PriorityBlockingQueue<>(100, zlecenieComparator);

    //TAXI
    private final ConcurrentHashMap<Integer, TaxiThread> taxiMap = new ConcurrentHashMap<>();

    private volatile Integer brokenTaxiId = null;
    private volatile boolean shuttingDownDyspozytornia = false;


    @Override
    public void flota(Set<Taxi> flota) {
        for (Taxi taxi : flota) {
            TaxiThread taxiThread = new TaxiThread(taxi, zlecenieQueue, this);
            taxiMap.put(taxi.numer(), taxiThread);
            Thread thread = new Thread(taxiThread);
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public int zlecenie() {
        int id = zlecenieId.incrementAndGet();
        Zlecenie zlecenie = new Zlecenie(id, 0, System.currentTimeMillis());
        zlecenieQueue.offer(zlecenie);

        //todo: budzimy wątki które sa uśpione bo czekały na zlecenia nowe

        return id;
    }

    @Override
    public void awaria(int numer, int numerZlecenia) {
        TaxiThread taxiThread = taxiMap.get(numer);
        if (taxiThread != null) {
            if (taxiThread.getState() == TaxiState.BROKEN) throw new RuntimeException("Broken Taxi cannot be broken again");
            else if (brokenTaxiId != null) throw new RuntimeException("Two taxi cannot be broken at the same time");
            else {
                taxiThread.markTaxiAsBroken();

                Zlecenie zlecenie = new Zlecenie(numerZlecenia, 1, System.currentTimeMillis());
                zlecenieQueue.offer(zlecenie);
                brokenTaxiId = numer;

                //todo: budzimy wątki czekające na zadania -> a ten thread pobranu tutaj powinien się sam zatrzymać
            }
        } else {
            throw new RuntimeException("Taxi thread not found");
        }
    }

    @Override
    public void naprawiono(int numer) {
        TaxiThread taxiThread = taxiMap.get(numer);
        if (taxiThread != null) {
            if (taxiThread.getState() != TaxiState.BROKEN) throw new RuntimeException("Taxi not broken");
            else taxiThread.markTaxiAsRepaired();

        } else {
            throw new RuntimeException("Taxi thread not found");
        }


    }

    @Override
    public Set<Integer> koniecPracy() {
        for (TaxiThread taxiThread : taxiMap.values()) {
            taxiThread.stop();
        }

        Set<Integer> resztaPracy = new HashSet<>();
        zlecenieQueue.forEach(zlecenie -> {
            resztaPracy.add(zlecenie.id());
        });
        return resztaPracy;
    }
}


class TaxiThread implements Runnable {
    private final Taxi taxi;
    private final PriorityBlockingQueue<Zlecenie> zlecenieQueue;
    private final Dyspozytornia dyspozytornia;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition awariaQueue = lock.newCondition();
    private final Condition noweZlecenieQueue = lock.newCondition();

    private volatile TaxiState state = TaxiState.WAITING;
    private volatile boolean stopped = false;

    TaxiThread(Taxi taxi, PriorityBlockingQueue<Zlecenie> kolejka,
              DyspozytorniaWatkowa dyspozytornia) {
        this.taxi = taxi;
        this.zlecenieQueue = kolejka;
        this.dyspozytornia = dyspozytornia;
    }

    @Override
    public void run() {



    }

    void markTaxiAsBroken() {
        lock.lock();
        try {
            state = TaxiState.BROKEN;
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsRepaired() {
        lock.lock();
        try {
            state = TaxiState.WAITING;
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsRunning() {
        lock.lock();
        try {
            state = TaxiState.RUNNING;
        } finally {
            lock.unlock();
        }
    }

    void stop() {
        stopped = true;
        lock.lock();
        try {
            awariaQueue.signal();
            noweZlecenieQueue.signal();
        } finally {
            lock.unlock();
        }
    }

    public TaxiState getState() { return state; }
}

record Zlecenie(int id, int priority, long createdAt) {}
enum TaxiState { WAITING, RUNNING, BROKEN }







