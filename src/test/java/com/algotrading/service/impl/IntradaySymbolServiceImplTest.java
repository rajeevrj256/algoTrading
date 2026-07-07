package com.algotrading.service.impl;

import com.algotrading.config.FnoProperties;
import com.algotrading.entity.IntradaySymbolEntity;
import com.algotrading.repository.IntradaySymbolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        IntradaySymbolServiceImpl service = new IntradaySymbolServiceImpl(repository, fnoDisabled());

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
        IntradaySymbolServiceImpl service = new IntradaySymbolServiceImpl(repository, fnoDisabled());

        ReflectionTestUtils.setField(service, "fallbackSymbolsStr", "RELIANCE, INFY , infy");
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 30L);
        ReflectionTestUtils.setField(service, "fallbackEnabled", true);

        when(repository.findEligibleSymbols(any(LocalDateTime.class))).thenReturn(Collections.emptyList());

        List<String> symbols = service.getSymbolsForScan();

        assertEquals(Arrays.asList("RELIANCE", "INFY"), symbols);
    }

    @Test
    void getFnoUnderlyingsReturnsConfiguredIndicesAndScanExcludesThem() {
        IntradaySymbolRepository repository = mock(IntradaySymbolRepository.class);
        FnoProperties fno = new FnoProperties();
        fno.setEnabled(true);
        Map<String, FnoProperties.Underlying> underlyings = new LinkedHashMap<>();
        underlyings.put("NIFTY", new FnoProperties.Underlying());
        underlyings.put("BANKNIFTY", new FnoProperties.Underlying());
        fno.setUnderlyings(underlyings);

        IntradaySymbolServiceImpl service = new IntradaySymbolServiceImpl(repository, fno);
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 30L);
        ReflectionTestUtils.setField(service, "fallbackEnabled", true);

        // DB shortlist mixes an index (NIFTY_50) with equities — the equity scan must drop the index.
        when(repository.findEligibleSymbols(any(LocalDateTime.class))).thenReturn(Arrays.asList(
                IntradaySymbolEntity.builder().symbol("NIFTY_50").active(true).build(),
                IntradaySymbolEntity.builder().symbol("TCS").active(true).build(),
                IntradaySymbolEntity.builder().symbol("INFY").active(true).build()
        ));

        assertEquals(Arrays.asList("TCS", "INFY"), service.getSymbolsForScan());
        assertEquals(Arrays.asList("NIFTY", "BANKNIFTY"), service.getFnoUnderlyings());
    }

    private static FnoProperties fnoDisabled() {
        FnoProperties fno = new FnoProperties();
        fno.setEnabled(false);
        return fno;
    }
}
