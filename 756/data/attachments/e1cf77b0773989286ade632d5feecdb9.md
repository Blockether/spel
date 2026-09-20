## DELETE http://localhost:34753/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 20 Sep 2026 13:27:36 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:34753/echo' \
  -X DELETE
```
