## POST http://localhost:44759/echo → 200 OK

### Request Body
```json
{
  "name": "Alice",
  "email": "alice@example.org"
}
```

### Response Headers
```
content-length: 84
content-type: application/json
date: Fri, 30 Oct 2026 11:14:32 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Alice",
    "email": "alice@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:44759/echo' \
  -X POST \
  -d '{
  "name": "Alice",
  "email": "alice@example.org"
}'
```
