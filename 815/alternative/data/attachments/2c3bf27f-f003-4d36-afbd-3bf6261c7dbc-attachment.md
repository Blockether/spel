## PATCH http://localhost:38681/echo → 200 OK

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
date: Tue, 03 Nov 2026 22:23:54 GMT
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
curl 'http://localhost:38681/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
