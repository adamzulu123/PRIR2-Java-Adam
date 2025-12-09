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

    //zlecenia -> ta kolejka ma wewnętrzny RenstrantLock
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

        for (TaxiThread taxi : taxiMap.values()) {
            taxi.notifyWorkersWithNewZlecenie();
        }

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

                for (TaxiThread taxi : taxiMap.values()) {
                    taxi.notifyWorkersWithNewZlecenie();
                }
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
        shuttingDownDyspozytornia = true;
        for (TaxiThread taxiThread : taxiMap.values()) {
            taxiThread.stop();
        }

        Set<Integer> resztaPracy = new HashSet<>();
        zlecenieQueue.forEach(zlecenie -> {
            resztaPracy.add(zlecenie.id());
        });
        return resztaPracy;
    }

    public boolean isShuttingDownDyspozytornia(){
        return shuttingDownDyspozytornia;
    }
}

class TaxiThread implements Runnable {
    private final Taxi taxi;
    private final PriorityBlockingQueue<Zlecenie> zlecenieQueue;
    private final DyspozytorniaWatkowa dyspozytornia;

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
        while (!stopped && !dyspozytornia.isShuttingDownDyspozytornia()) {
            Zlecenie zlecenie = null;

            lock.lock();
            try {
                while (state == TaxiState.BROKEN && !stopped && !dyspozytornia.isShuttingDownDyspozytornia()) {
                    awariaQueue.await();
                }

                if (stopped || dyspozytornia.isShuttingDownDyspozytornia()) break;

                while (state == TaxiState.WAITING && !stopped && !dyspozytornia.isShuttingDownDyspozytornia()) {
                    zlecenie = zlecenieQueue.poll();
                    if (zlecenie != null) {
                        markTaxiAsRunning();
                        break;
                    }
                    noweZlecenieQueue.await(); //kolejka pusta -> czekamy dalej
                }

                if (stopped || dyspozytornia.isShuttingDownDyspozytornia()) break;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }finally {
                lock.unlock();
            }

            if (zlecenie != null) {
                try {
                    taxi.wykonajZlecenie(zlecenie.id());
                } finally {
                    try {
                        lock.lock();
                        if (state != TaxiState.BROKEN && state == TaxiState.RUNNING) {
                            state = TaxiState.WAITING;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
    }

    void notifyWorkersWithNewZlecenie(){
        lock.lock();
        try {
            noweZlecenieQueue.signal();
        } finally {
            lock.unlock();
        }
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
            awariaQueue.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsRunning() {
        state = TaxiState.RUNNING;
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







