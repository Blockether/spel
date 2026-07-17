## PATCH http://localhost:38837/echo → 200 OK

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
date: Fri, 17 Jul 2026 15:37:30 GMT
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
curl 'http://localhost:38837/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
