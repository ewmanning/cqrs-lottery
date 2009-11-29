package com.xebia.cqrs.dao;

import static java.util.Arrays.*;
import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import com.xebia.cqrs.bus.Bus;
import com.xebia.cqrs.domain.AggregateRootNotFoundException;
import com.xebia.cqrs.domain.Event;
import com.xebia.cqrs.domain.FakeAggregateRoot;
import com.xebia.cqrs.domain.GreetingEvent;
import com.xebia.cqrs.domain.VersionedId;
import com.xebia.cqrs.eventstore.EventStore;


public class RepositoryImplTest {

    private static final VersionedId TEST_ID = VersionedId.random().withVersion(2);
    
    private Bus bus;
    private FakeAggregateRoot aggregateRoot;
    private EventStore<Event> eventStore;
    private RepositoryImpl subject;
    
    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        aggregateRoot = new FakeAggregateRoot(TEST_ID.nextVersion());
        aggregateRoot.loadFromHistory(asList(
                new GreetingEvent(TEST_ID, "Hi Erik"),
                new GreetingEvent(TEST_ID, "Hi Sjors")));
        eventStore = createNiceMock(EventStore.class);
        bus = createNiceMock(Bus.class);
        subject = new RepositoryImpl(eventStore, bus);
    }
    
    @Test
    public void shouldFailOnNonExistingAggregateRoot() {
        expect(eventStore.loadEventSource(FakeAggregateRoot.class, TEST_ID)).andReturn(null);
        replay(eventStore);

        try {
            subject.get(FakeAggregateRoot.class, TEST_ID);
            fail("AggregateRootNotFoundException expected");
        } catch (AggregateRootNotFoundException expected) {
            verify(eventStore);
            assertEquals(FakeAggregateRoot.class.getName(), expected.getAggregateRootType());
            assertEquals(TEST_ID.getId(), expected.getAggregateRootId());
        }
    }
    
    @Test
    public void shouldLoadAggregateRootFromEventStore() {
        expect(eventStore.loadEventSource(FakeAggregateRoot.class, TEST_ID)).andReturn(aggregateRoot);
        replay(eventStore);
        
        FakeAggregateRoot result = subject.get(FakeAggregateRoot.class, TEST_ID);

        verify(eventStore);
        assertSame(aggregateRoot, result);
    }
    
    @Test
    public void shouldLoadAggregateOnlyOnce() {
        expect(eventStore.loadEventSource(FakeAggregateRoot.class, TEST_ID)).andReturn(aggregateRoot);
        replay(eventStore);
        
        FakeAggregateRoot a = subject.get(FakeAggregateRoot.class, TEST_ID);
        FakeAggregateRoot b = subject.get(FakeAggregateRoot.class, TEST_ID);

        verify(eventStore);
        assertSame(a, b);
    }
    
    @Test
    public void shouldRejectDifferentAggregatesWithSameId() {
        FakeAggregateRoot a = new FakeAggregateRoot(TEST_ID);
        FakeAggregateRoot b = new FakeAggregateRoot(TEST_ID);
        
        subject.add(a);
        try {
            subject.add(b);
            fail("IllegalStateException expected");
        } catch (IllegalStateException expected) {
        }
    }
    
    @Test
    public void shouldCheckAggregateVersionOnLoadFromSession() {
        expect(eventStore.loadEventSource(FakeAggregateRoot.class, TEST_ID)).andReturn(aggregateRoot);
        eventStore.verifyVersion(aggregateRoot, TEST_ID.withVersion(0)); expectLastCall().andThrow(new OptimisticLockingFailureException("error"));
        replay(eventStore);
        
        subject.get(FakeAggregateRoot.class, TEST_ID);
        try {
            subject.get(FakeAggregateRoot.class, TEST_ID.withVersion(0));
            fail("OptimisticLockingFailureException expected");
        } catch (OptimisticLockingFailureException expected) {
        }
        
        verify(eventStore);
    }
    
    @Test
    public void shouldStoreAddedAggregate() {
        aggregateRoot.greetPerson("Erik");

        eventStore.storeEventSource(same(aggregateRoot)); expectLastCall();
        replay(eventStore, bus);
        
        subject.add(aggregateRoot);
        subject.afterHandleMessage();
        
        verify(eventStore, bus);
    }
    
    @Test
    public void shouldStoreLoadedAggregate() {
        expect(eventStore.loadEventSource(FakeAggregateRoot.class, TEST_ID)).andReturn(aggregateRoot);
        eventStore.storeEventSource(same(aggregateRoot)); expectLastCall();
        replay(eventStore, bus);

        FakeAggregateRoot result = subject.get(FakeAggregateRoot.class, TEST_ID);
        result.greetPerson("Erik");

        subject.afterHandleMessage();
        
        verify(eventStore, bus);
    }
    
    @Test
    public void shouldPublishChangeEventsOnSave() {
        aggregateRoot.greetPerson("Erik");

        bus.publish(eq(aggregateRoot.getUnsavedEvents())); expectLastCall();
        replay(eventStore, bus);
        
        subject.add(aggregateRoot);
        subject.afterHandleMessage();
        
        verify(eventStore, bus);
    }
    
    @Test
    public void shouldReplyWithNotificationsOnSave() {
        aggregateRoot.greetPerson("Erik");

        bus.reply(eq(aggregateRoot.getNotifications())); expectLastCall();
        replay(eventStore, bus);
        
        subject.add(aggregateRoot);
        subject.afterHandleMessage();
        
        verify(eventStore, bus);
    }
    
}
