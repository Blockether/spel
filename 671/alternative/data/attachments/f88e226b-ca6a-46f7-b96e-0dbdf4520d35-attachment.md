## PATCH http://localhost:42337/echo → 200 OK

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Tue, 28 Jul 2026 17:00:25 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:42337/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
