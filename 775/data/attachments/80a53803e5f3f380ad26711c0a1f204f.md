## PUT http://localhost:41381/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Sat, 03 Oct 2026 14:55:53 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:41381/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
