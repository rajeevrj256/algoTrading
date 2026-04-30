package com.algotrading.service.impl;

import com.algotrading.entity.IntradaySymbolEntity;
import com.algotrading.repository.IntradaySymbolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntradaySymbolServiceImplTest {

    @Test
    void getSymbolsForScanUsesDatabaseShortlistAndCachesIt() {
        IntradaySymbolRepository repository = mock(IntradaySymbolRepository.class);
        IntradaySymbolServiceImpl service = new IntradaySymbolServiceImpl(repository);

        ReflectionTestUtils.setField(service, "fallbackSymbolsStr", "RELIANCE,INFY");
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 30L);
        ReflectionTestUtils.setField(service, "fallbackEnabled", true);

        when(repository.findEligibleSymbols(any(LocalDateTime.class))).thenReturn(Arrays.asList(
                IntradaySymbolEntity.builder().symbol("tcs").score(92).active(true).build(),
                IntradaySymbolEntity.builder().symbol("infy").score(80).active(true).build()
        ));

        List<String> first = service.getSymbolsForScan();
        List<String> second = service.getSymbolsForScan();

        assertEquals(Arrays.asList("TCS", "INFY"), first);
        assertEquals(first, second);
        verify(repository, times(1)).findEligibleSymbols(any(LocalDateTime.class));
    }

    @Test
    void getSymbolsForScanFallsBackToConfiguredListWhenDbIsEmpty() {
        IntradaySymbolRepository repository = mock(IntradaySymbolRepository.class);
        IntradaySymbolServiceImpl service = new IntradaySymbolServiceImpl(repository);

        ReflectionTestUtils.setField(service, "fallbackSymbolsStr", "RELIANCE, INFY , infy");
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 30L);
        ReflectionTestUtils.setField(service, "fallbackEnabled", true);

        when(repository.findEligibleSymbols(any(LocalDateTime.class))).thenReturn(Collections.emptyList());

        List<String> symbols = service.getSymbolsForScan();

        assertEquals(Arrays.asList("RELIANCE", "INFY"), symbols);
    }
}
