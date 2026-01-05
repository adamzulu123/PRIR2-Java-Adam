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

public class DyspozytorniaWatkowa2 implements Dyspozytornia {
    // Zlecenia
    private final AtomicInteger zlecenieId = new AtomicInteger(0);
    private final Comparator<Zlecenie2> zlecenieComparator = Comparator
            .comparing(Zlecenie2::priority).reversed()
            .thenComparing(Zlecenie2::createdAt);
    private final PriorityBlockingQueue<Zlecenie2> zlecenieQueue = new PriorityBlockingQueue<>(100, zlecenieComparator);
    private final ConcurrentHashMap<Integer, Integer> assignedZlecenia = new ConcurrentHashMap<>();

    // Taxi
    private final ConcurrentHashMap<Integer, TaxiThread2> allTaxis = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Integer> availableTaxis = new ConcurrentLinkedQueue<>();
    private final ReentrantLock taxiLock = new ReentrantLock();
    private Integer brokenTaxiId = null;

    // Dyspozytornia
    private volatile boolean shuttingDownDyspozytornia = false;

    @Override
    public void flota(Set<Taxi> flota) {
        for (Taxi taxi : flota) {
            TaxiThread2 taxiThread = new TaxiThread2(taxi, this);
            allTaxis.put(taxi.numer(), taxiThread);
            availableTaxis.offer(taxi.numer());
            Thread thread = new Thread(taxiThread);
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public int zlecenie() {
        int id = zlecenieId.incrementAndGet();
        Zlecenie2 zlecenie = new Zlecenie2(id, 0, Instant.now());
        zlecenieQueue.offer(zlecenie);
        tryAssignZlecenie();
        return id;
    }

    @Override
    public void awaria(int numer, int numerZlecenia) {
        TaxiThread2 taxiThread = allTaxis.get(numer);
        if (taxiThread == null) return;

        taxiLock.lock();
        try {
            if (brokenTaxiId != null) return;

            Integer wasAssigned = assignedZlecenia.get(numerZlecenia);

            if (wasAssigned != null && wasAssigned.equals(numer)) {
                brokenTaxiId = numer;
                assignedZlecenia.remove(numerZlecenia);

                Zlecenie2 zlecenie = new Zlecenie2(numerZlecenia, 1, Instant.now());
                zlecenieQueue.offer(zlecenie);
            } else {
                brokenTaxiId = numer;
            }
        } finally {
            taxiLock.unlock();
        }

        taxiThread.markTaxiAsBroken();
        tryAssignZlecenie();
    }

    @Override
    public void naprawiono(int numer) {
        TaxiThread2 taxiThread = allTaxis.get(numer);
        if (taxiThread == null) return;

        taxiLock.lock();
        try {
            if (brokenTaxiId == null || brokenTaxiId != numer) return;
            brokenTaxiId = null;
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

        for (TaxiThread2 taxiThread : allTaxis.values()) {
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
        if (shuttingDownDyspozytornia) return;
        if (zlecenieQueue.isEmpty()) return;

        taxiLock.lock();
        try {
            if (shuttingDownDyspozytornia) return;

            int skippingBrokenTaxi = 0;

            while (!zlecenieQueue.isEmpty() && !availableTaxis.isEmpty()) {
                Integer taxiNumer = availableTaxis.poll();
                if (taxiNumer == null) break;

                if (taxiNumer.equals(brokenTaxiId)) {
                    availableTaxis.offer(taxiNumer);
                    skippingBrokenTaxi++;

                    if (skippingBrokenTaxi > 1) {
                        break;
                    }
                    continue;
                }

                skippingBrokenTaxi = 0;

                Zlecenie2 zlecenie = zlecenieQueue.poll();
                if (zlecenie == null) {
                    availableTaxis.offer(taxiNumer);
                    break;
                }

                TaxiThread2 taxiThread = allTaxis.get(taxiNumer);
                if (taxiThread != null && taxiThread.getState() != TaxiState2.BROKEN) {
                    assignedZlecenia.put(zlecenie.id(), taxiNumer);
                    taxiThread.assignZlecenie(zlecenie);
                } else {
                    zlecenieQueue.offer(zlecenie);
                    availableTaxis.offer(taxiNumer);
                }
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
                TaxiThread2 taxiThread = allTaxis.get(taxiNumer);
                if (taxiThread != null && taxiThread.getState() != TaxiState2.BROKEN) {
                    availableTaxis.offer(taxiNumer);
                }
            }
        } finally {
            taxiLock.unlock();
        }

        if (!shuttingDownDyspozytornia) {
            tryAssignZlecenie();
        }
    }
}

class TaxiThread2 implements Runnable {
    private final Taxi taxi;
    private final DyspozytorniaWatkowa2 dyspozytornia;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition awariaCondition = lock.newCondition();
    private final Condition noweZlecenieCondition = lock.newCondition();

    private volatile TaxiState2 state = TaxiState2.WAITING;
    private volatile Zlecenie2 currentZlecenie = null;

    TaxiThread2(Taxi taxi, DyspozytorniaWatkowa2 dyspozytornia) {
        this.taxi = taxi;
        this.dyspozytornia = dyspozytornia;
    }

    @Override
    public void run() {
        while (!dyspozytornia.isShuttingDownDyspozytornia()) {
            Zlecenie2 zlecenieToDo = getOrWaitForZlecenie();

            if (zlecenieToDo != null) {
                int executedZlecenieId = zlecenieToDo.id();
                try {
                    taxi.wykonajZlecenie(executedZlecenieId);
                } finally {
                    lock.lock();
                    try {
                        if (state != TaxiState2.BROKEN && state == TaxiState2.RUNNING) {
                            state = TaxiState2.WAITING;
                            dyspozytornia.taxiAfterZlecenie(taxi.numer(), executedZlecenieId);
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
    }

    private Zlecenie2 getOrWaitForZlecenie() {
        lock.lock();
        try {
            while (state == TaxiState2.BROKEN && !dyspozytornia.isShuttingDownDyspozytornia()) {
                awariaCondition.await();
            }

            if (dyspozytornia.isShuttingDownDyspozytornia()) return null;

            while (currentZlecenie == null && !dyspozytornia.isShuttingDownDyspozytornia()) {
                noweZlecenieCondition.await();
            }

            if (dyspozytornia.isShuttingDownDyspozytornia()) return null;

            if (currentZlecenie != null) {
                Zlecenie2 zlecenieToDo = currentZlecenie;
                currentZlecenie = null;
                state = TaxiState2.RUNNING;
                return zlecenieToDo;
            }

            return null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    void assignZlecenie(Zlecenie2 zlecenie) {
        lock.lock();
        try {
            this.currentZlecenie = zlecenie;
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsBroken() {
        lock.lock();
        try {
            state = TaxiState2.BROKEN;
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsRepaired() {
        lock.lock();
        try {
            state = TaxiState2.WAITING;
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

    TaxiState2 getState() { return state; }
}

record Zlecenie2(int id, int priority, Instant createdAt) {}
enum TaxiState2 { WAITING, RUNNING, BROKEN }