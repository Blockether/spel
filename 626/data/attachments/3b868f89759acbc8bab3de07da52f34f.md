## DELETE http://localhost:39001/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 07 Jul 2026 11:12:21 GMT
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
curl 'http://localhost:39001/echo' \
  -X DELETE
```
