import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class DyspozytorniaWatkowa3 implements Dyspozytornia {
    // Zlecenia
    private final AtomicInteger zlecenieId = new AtomicInteger(0);
    private final Comparator<Zlecenie3> zlecenieComparator = Comparator
            .comparing(Zlecenie3::priority).reversed()
            .thenComparing(Zlecenie3::createdAt);
    private final PriorityBlockingQueue<Zlecenie3> zlecenieQueue = new PriorityBlockingQueue<>(100, zlecenieComparator);
    private final ConcurrentHashMap<Integer, Integer> assignedZlecenia = new ConcurrentHashMap<>();

    // Taxi
    private final ConcurrentHashMap<Integer, TaxiThread3> allTaxis = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Integer> availableTaxis = new ConcurrentLinkedQueue<>();
    private final Set<Integer> availableTaxisSet = new HashSet<>();
    private final ReentrantLock taxiLock = new ReentrantLock();
    private Integer brokenTaxiId = null;

    // Dyspozytornia
    private volatile boolean shuttingDownDyspozytornia = false;

    @Override
    public void flota(Set<Taxi> flota) {
        taxiLock.lock();
        try {
            for (Taxi taxi : flota) {
                TaxiThread3 taxiThread = new TaxiThread3(taxi, this);
                allTaxis.put(taxi.numer(), taxiThread);
                safeOfferTaxiToTheQueue(taxi.numer());

                Thread thread = new Thread(taxiThread);
                thread.setDaemon(true);
                thread.start();
            }
        } finally {
            taxiLock.unlock();
        }
    }

    @Override
    public int zlecenie() {
        int id = zlecenieId.incrementAndGet();
        Zlecenie3 zlecenie = new Zlecenie3(id, 0, Instant.now());
        zlecenieQueue.offer(zlecenie);
        tryAssignZlecenie();
        return id;
    }

    @Override
    public void awaria(int numer, int numerZlecenia) {
        TaxiThread3 taxiThread = allTaxis.get(numer);
        if (taxiThread == null) return;

        taxiLock.lock();
        try {
            if (brokenTaxiId != null && brokenTaxiId != numer) {
                return;
            }
            brokenTaxiId = numer;

            Integer wasAssigned = assignedZlecenia.get(numerZlecenia);

            if (wasAssigned != null && wasAssigned.equals(numer)) {
                assignedZlecenia.remove(numerZlecenia);

                Zlecenie3 zlecenie = new Zlecenie3(numerZlecenia, 1, Instant.now());
                zlecenieQueue.offer(zlecenie);
            }
        } finally {
            taxiLock.unlock();
        }

        taxiThread.markTaxiAsBroken();
        tryAssignZlecenie();
    }

    @Override
    public void naprawiono(int numer) {
        TaxiThread3 taxiThread = allTaxis.get(numer);
        if (taxiThread == null) return;

        taxiLock.lock();
        try {
            if (brokenTaxiId == null || brokenTaxiId != numer) return;
            brokenTaxiId = null;
            safeOfferTaxiToTheQueue(numer);
        } finally {
            taxiLock.unlock();
        }

        taxiThread.markTaxiAsRepaired();
        tryAssignZlecenie();
    }

    @Override
    public Set<Integer> koniecPracy() {
        taxiLock.lock();
        try {
            shuttingDownDyspozytornia = true;
        } finally {
            taxiLock.unlock();
        }

        for (TaxiThread3 taxiThread : allTaxis.values()) {
            taxiThread.stop();
        }

        Set<Integer> restOfWork = new HashSet<>();
        while (!zlecenieQueue.isEmpty()) {
            restOfWork.add(zlecenieQueue.poll().id());
        }

        return restOfWork;
    }

    boolean isShuttingDownDyspozytornia() {
        return shuttingDownDyspozytornia;
    }

    private void tryAssignZlecenie() {
        if (shuttingDownDyspozytornia || zlecenieQueue.isEmpty() || availableTaxis.isEmpty()) return;

        taxiLock.lock();
        try {
            if (shuttingDownDyspozytornia) return;

            while (!zlecenieQueue.isEmpty() && !availableTaxis.isEmpty()) {
                Integer taxiNumer = availableTaxis.poll();
                if (taxiNumer == null) break;

                availableTaxisSet.remove(taxiNumer);

                TaxiThread3 taxiThread = allTaxis.get(taxiNumer);
                if (taxiThread == null || taxiThread.getState() == TaxiState3.BROKEN || taxiNumer.equals(brokenTaxiId)) {
                    continue;
                }

                Zlecenie3 zlecenie = zlecenieQueue.poll();
                if (zlecenie == null) {
                    safeOfferTaxiToTheQueue(taxiNumer);
                    break;
                }

                assignedZlecenia.put(zlecenie.id(), taxiNumer);
                taxiThread.assignZlecenie(zlecenie);
            }
        } finally {
            taxiLock.unlock();
        }
    }

    void taxiAfterZlecenie(int taxiNumer, int zlecenieId) {
        taxiLock.lock();
        try {
            assignedZlecenia.remove(zlecenieId);

            if (!shuttingDownDyspozytornia) {
                TaxiThread3 taxiThread = allTaxis.get(taxiNumer);
                if (taxiThread != null
                        && taxiThread.getState() != TaxiState3.BROKEN
                        && !Integer.valueOf(taxiNumer).equals(brokenTaxiId)) {
                    safeOfferTaxiToTheQueue(taxiNumer);
                }
            }
        } finally {
            taxiLock.unlock();
        }

        if (!shuttingDownDyspozytornia) {
            tryAssignZlecenie();
        }
    }

    private void safeOfferTaxiToTheQueue(int numer) {
        if (availableTaxisSet.add(numer)) {
            availableTaxis.offer(numer);
        }
    }
}

class TaxiThread3 implements Runnable {
    private final Taxi taxi;
    private final DyspozytorniaWatkowa3 dyspozytornia;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition awariaCondition = lock.newCondition();
    private final Condition noweZlecenieCondition = lock.newCondition();

    private volatile TaxiState3 state = TaxiState3.WAITING;
    private volatile Zlecenie3 currentZlecenie = null;

    TaxiThread3(Taxi taxi, DyspozytorniaWatkowa3 dyspozytornia) {
        this.taxi = taxi;
        this.dyspozytornia = dyspozytornia;
    }

    @Override
    public void run() {
        while (!dyspozytornia.isShuttingDownDyspozytornia()) {
            Zlecenie3 zlecenieToDo = getOrWaitForZlecenie();

            if (zlecenieToDo != null) {
                int executedZlecenieId = zlecenieToDo.id();
                try {
                    taxi.wykonajZlecenie(executedZlecenieId);
                } finally {
                    boolean notifyDyspozytornia = false;
                    lock.lock();
                    try {
                        if (state != TaxiState3.BROKEN) {
                            state = TaxiState3.WAITING;
                            notifyDyspozytornia = true;
                        }
                    } finally {
                        lock.unlock();
                    }
                    if (notifyDyspozytornia) {
                        dyspozytornia.taxiAfterZlecenie(taxi.numer(), executedZlecenieId);
                    }
                }
            }
        }
    }

    private Zlecenie3 getOrWaitForZlecenie() {
        lock.lock();
        try {
            while (true) {
                if (dyspozytornia.isShuttingDownDyspozytornia()) return null;

                if (state == TaxiState3.BROKEN) {
                    try {
                        awariaCondition.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }

                if (currentZlecenie != null) {
                    Zlecenie3 zlecenieToDo = currentZlecenie;
                    currentZlecenie = null;
                    state = TaxiState3.RUNNING;
                    return zlecenieToDo;
                }

                try {
                    noweZlecenieCondition.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    void assignZlecenie(Zlecenie3 zlecenie) {
        lock.lock();
        try {
            this.currentZlecenie = zlecenie;
            this.state = TaxiState3.RUNNING;
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsBroken() {
        lock.lock();
        try {
            state = TaxiState3.BROKEN;
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsRepaired() {
        lock.lock();
        try {
            state = TaxiState3.WAITING;
            awariaCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void stop() {
        lock.lock();
        try {
            awariaCondition.signal();
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    TaxiState3 getState() { return state; }
}

record Zlecenie3(int id, int priority, Instant createdAt) {}
enum TaxiState3 { WAITING, RUNNING, BROKEN }