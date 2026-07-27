// Fiori Elements List Report / Object Page annotations (see ARCHITECTURE.md "UI").
// The published scenarios are service behaviors; these annotations keep the OData
// services ready for Fiori Elements frontends without a bespoke UI5 app.
using { PolicyService, ClaimsService, QuoteService } from './services';

annotate PolicyService.Policies with @(
  UI: {
    HeaderInfo: {
      TypeName      : 'Policy',
      TypeNamePlural: 'Policies',
      Title         : { Value: policyNumber },
      Description   : { Value: namedInsuredName }
    },
    LineItem: [
      { Value: policyNumber },
      { Value: lineOfBusiness },
      { Value: plan },
      { Value: status },
      { Value: effectiveDate },
      { Value: expirationDate },
      { Value: premium }
    ],
    SelectionFields: [ status, lineOfBusiness ]
  }
);

annotate ClaimsService.Claims with @(
  UI: {
    HeaderInfo: {
      TypeName      : 'Claim',
      TypeNamePlural: 'Claims',
      Title         : { Value: claimNumber },
      Description   : { Value: status }
    },
    LineItem: [
      { Value: claimNumber },
      { Value: claimType },
      { Value: status },
      { Value: dateOfLoss },
      { Value: estimatedDamage },
      { Value: adjusterRoute }
    ],
    SelectionFields: [ status, claimType ]
  }
);

annotate QuoteService.AutoQuotes with @(
  UI: {
    HeaderInfo: {
      TypeName      : 'Auto Quote',
      TypeNamePlural: 'Auto Quotes',
      Title         : { Value: driverName }
    },
    LineItem: [
      { Value: driverName },
      { Value: vehicleMake },
      { Value: vehicleModel },
      { Value: status },
      { Value: createdAt }
    ]
  }
);
