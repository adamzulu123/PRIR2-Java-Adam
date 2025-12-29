import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class DyspozytorniaWatkowa implements Dyspozytornia {
    private final AtomicInteger zlecenieId = new AtomicInteger(0);
    private final AtomicLong timeAdded = new AtomicLong();

    //zlecenia -> ta kolejka ma wewnętrzny RenstrantLock
    Comparator<Zlecenie> zlecenieComparator = Comparator
            .comparing(Zlecenie::priority).reversed()
            .thenComparing(Zlecenie::createdAt);
    private final PriorityBlockingQueue<Zlecenie> zlecenieQueue = new PriorityBlockingQueue<>(100, zlecenieComparator);

    // śledzenie przydzielonych zleceń: Zlecenie ID -> Taxi numer
    private final ConcurrentHashMap<Integer, Integer> assignedOrders = new ConcurrentHashMap<>(); //todo: upewnić się czy to niezbędne

    // Taxi
    private final ConcurrentHashMap<Integer, TaxiThread> allTaxis = new ConcurrentHashMap<>();
    //todo: czy te avaiableTaxis oraz busyTaxis moga być queue?  w ten sposób nie będzie głodzenia wątków (do sprawdzenia)
    // czy wgl busyTaxis jest potrzebne jak nigdzie tego nie sprawdzamy i tak? tak samo lock, jeśli uzyć BlockingQueue (bo ona ma wbudowany lock)
    // BlockingQueue<Integer> availableTaxis = new LinkedBlockingQueue<>();
    private final Set<Integer> availableTaxis = ConcurrentHashMap.newKeySet();
    private final Set<Integer> busyTaxis = ConcurrentHashMap.newKeySet();

    private final ReentrantLock taxiLock = new ReentrantLock();

    private volatile Integer brokenTaxiId = null; // tylko jedno taxi broken, to na wszelki
    private volatile boolean shuttingDownDyspozytornia = false;

    @Override
    public void flota(Set<Taxi> flota) {
        for (Taxi taxi : flota) {
            TaxiThread taxiThread = new TaxiThread(taxi, this);
            allTaxis.put(taxi.numer(), taxiThread);
            availableTaxis.add(taxi.numer());
            Thread thread = new Thread(taxiThread);
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public int zlecenie() {
        if (shuttingDownDyspozytornia) {
            throw new IllegalStateException("Dyspozytornia is shutting down");
        }
        int id = zlecenieId.incrementAndGet();
        Zlecenie zlecenie = new Zlecenie(id, 0, timeAdded.incrementAndGet());
        zlecenieQueue.offer(zlecenie);

        tryReAssignZlecenie();
        return id;
    }

    @Override
    public void awaria(int numer, int numerZlecenia) {
        TaxiThread taxiThread = allTaxis.get(numer);
        if (taxiThread == null) throw new RuntimeException("Taxi thread not found");
        if (taxiThread.getState() == TaxiState.BROKEN) throw new RuntimeException("Broken Taxi cannot be broken again");
        if (brokenTaxiId != null) throw new RuntimeException("Two taxi cannot be broken at the same time");

        taxiThread.markTaxiAsBroken();
        brokenTaxiId = numer;

        taxiLock.lock();
        try {
            busyTaxis.remove(numer);
        } finally {
            taxiLock.unlock();
        }

        Integer wasAssigned = assignedOrders.remove(numerZlecenia);
        if (wasAssigned == null) {
            System.out.println("Zlecenie number: " + numerZlecenia + " wasn't assigned, only taxi will be marked as broken");
        } else {
            Zlecenie zlecenie = new Zlecenie(numerZlecenia, 1, timeAdded.incrementAndGet());
            zlecenieQueue.offer(zlecenie);
        }

        tryReAssignZlecenie();
    }

    @Override
    public void naprawiono(int numer) {
        TaxiThread taxiThread = allTaxis.get(numer);
        if (taxiThread == null) throw new RuntimeException("Taxi thread not found");
        if (taxiThread.getState() != TaxiState.BROKEN) throw new RuntimeException("Taxi not broken");

        taxiThread.markTaxiAsRepaired();
        brokenTaxiId = null;

        taxiLock.lock();
        try {
            availableTaxis.add(numer);
        } finally {
            taxiLock.unlock();
        }

        tryReAssignZlecenie();
    }

    @Override
    public Set<Integer> koniecPracy() {
        shuttingDownDyspozytornia = true;

        for (TaxiThread taxiThread : allTaxis.values()) {
            taxiThread.stop();
        }

        Set<Integer> resztaPracy = new HashSet<>();
        while (!zlecenieQueue.isEmpty()) {
            resztaPracy.add(zlecenieQueue.poll().id());
        }

        return resztaPracy;
    }

    public boolean isShuttingDownDyspozytornia() {
        return shuttingDownDyspozytornia;
    }

    //todo: do zmiany jak tylko BlockingQueue uzyjemy BlockingQueue<Integer> availableTaxis = new LinkedBlockingQueue<>();
    private void tryReAssignZlecenie() {
        if (shuttingDownDyspozytornia) return;

        taxiLock.lock();
        try {
            while (!zlecenieQueue.isEmpty() && !availableTaxis.isEmpty()) {
                Zlecenie zlecenie = zlecenieQueue.poll();
                if (zlecenie == null) break;

                Integer taxiNumer = availableTaxis.iterator().next();
                availableTaxis.remove(taxiNumer);
                busyTaxis.add(taxiNumer);

                TaxiThread taxiThread = allTaxis.get(taxiNumer);
                if (taxiThread != null) {
                    assignedOrders.put(zlecenie.id(), taxiNumer);
                    taxiThread.assignZlecenie(zlecenie);
                }
            }
        } finally {
            taxiLock.unlock();
        }
    }

    void onTaxiFinished(int taxiNumer, int zlecenieId) {
        if (shuttingDownDyspozytornia) return;

        assignedOrders.remove(zlecenieId);

        taxiLock.lock();
        try {
            TaxiThread taxiThread = allTaxis.get(taxiNumer);
            if (taxiThread != null && taxiThread.getState() != TaxiState.BROKEN) {
                busyTaxis.remove(taxiNumer);
                availableTaxis.add(taxiNumer);
            }
        } finally {
            taxiLock.unlock();
        }

        tryReAssignZlecenie(); // próbujemy przydzielić nowe
    }
}

class TaxiThread implements Runnable {
    private final Taxi taxi;
    private final DyspozytorniaWatkowa dyspozytornia;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition awariaCondition = lock.newCondition();
    private final Condition noweZlecenieCondition = lock.newCondition();

    private volatile TaxiState state = TaxiState.WAITING;

    //todo : sprawdzenie czy to 2 pole jest niezbędne czy da się uprościć
    private volatile Zlecenie currentZlecenie = null;
    private volatile boolean hasNewZlecenie = false;

    TaxiThread(Taxi taxi, DyspozytorniaWatkowa dyspozytornia) {
        this.taxi = taxi;
        this.dyspozytornia = dyspozytornia;
    }

    @Override
    public void run() {
        while (!dyspozytornia.isShuttingDownDyspozytornia()) {
            Zlecenie zlecenieToDo = null;

            lock.lock();
            try {
                // czekamy jeśli broken
                while (state == TaxiState.BROKEN && !dyspozytornia.isShuttingDownDyspozytornia()) {
                    awariaCondition.await();
                }

                if (dyspozytornia.isShuttingDownDyspozytornia()) break;

                // czekamy na nowe zlecenie
                while (!hasNewZlecenie && !dyspozytornia.isShuttingDownDyspozytornia()) {
                    noweZlecenieCondition.await();
                }

                if (dyspozytornia.isShuttingDownDyspozytornia()) break;

                // bierzemy zlecenie
                if (hasNewZlecenie && currentZlecenie != null) {
                    zlecenieToDo = currentZlecenie;
                    currentZlecenie = null;
                    hasNewZlecenie = false;
                    state = TaxiState.RUNNING;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                lock.unlock();
            }

            // wykonujemy zlecenie po za lock
            if (zlecenieToDo != null) {
                int executedZlecenieId = zlecenieToDo.id();
                try {
                    taxi.wykonajZlecenie(executedZlecenieId);
                } finally {
                    lock.lock();
                    try {
                        if (state != TaxiState.BROKEN && state == TaxiState.RUNNING) {
                            state = TaxiState.WAITING;
                            dyspozytornia.onTaxiFinished(taxi.numer(), executedZlecenieId);
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
    }

    void assignZlecenie(Zlecenie zlecenie) {
        lock.lock();
        try {
            this.currentZlecenie = zlecenie;
            this.hasNewZlecenie = true;
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsBroken() {
        lock.lock();
        try {
            state = TaxiState.BROKEN;
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsRepaired() {
        lock.lock();
        try {
            state = TaxiState.WAITING;
            awariaCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void stop() {
        lock.lock();
        try {
            awariaCondition.signalAll();
            noweZlecenieCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public TaxiState getState() {
        return state;
    }
}

record Zlecenie(int id, int priority, long createdAt) {}
enum TaxiState { WAITING, RUNNING, BROKEN }