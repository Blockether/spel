# Integration Notes

This test checks the **order pipeline** from submission to fulfillment.

## Steps

1. Submit an order with `POST /orders`.
2. Poll `/orders/:id/status` until it's `fulfilled`.
3. Verify the shipping label was generated.

## Sample Response

```json
{
  "orderId": "ord-1234",
  "status": "fulfilled",
  "tracking": "1Z999AA10123456784"
}
```

## References

- [Order API Spec](https://example.com/api/orders)
- See also the `checkout` domain notes.
> Heads up: the tracking number format depends on the carrier.
