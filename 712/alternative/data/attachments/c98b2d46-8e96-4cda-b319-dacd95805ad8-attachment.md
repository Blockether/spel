## PATCH http://localhost:37915/echo → 200 OK

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Mon, 17 Aug 2026 23:30:02 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:37915/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
