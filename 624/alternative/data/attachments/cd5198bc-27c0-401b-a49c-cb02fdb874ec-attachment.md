## DELETE http://localhost:32929/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 02 Jun 2026 10:45:26 GMT
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
curl 'http://localhost:32929/echo' \
  -X DELETE
```
