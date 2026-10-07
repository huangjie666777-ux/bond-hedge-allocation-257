package com.hedge257;

/**
 * Reproduction result for one bootstrapped instrument.
 *
 * @param id             instrument id
 * @param marketQuote    input deposit or fixed swap rate
 * @param reproducedQuote rate implied by the finished curve
 * @param residual       reproducedQuote - marketQuote
 */
public record InstrumentRepricing(String id,
                                  double marketQuote,
                                  double reproducedQuote,
                                  double residual) {
}
