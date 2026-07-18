## PATCH http://localhost:44081/echo → 200 OK

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
date: Sat, 18 Jul 2026 12:04:58 GMT
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
curl 'http://localhost:44081/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
