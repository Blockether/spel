## PUT http://localhost:43039/echo → 200 OK

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
date: Wed, 04 Nov 2026 05:36:59 GMT
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
curl 'http://localhost:43039/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
